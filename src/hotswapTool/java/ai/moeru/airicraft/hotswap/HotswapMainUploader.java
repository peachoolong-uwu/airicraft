package ai.moeru.airicraft.hotswap;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class HotswapMainUploader {
	private static final String CLASS_SUFFIX = ".class";
	private static final String AIRICRAFT_PREFIX = "ai/moeru/airicraft/";

	private HotswapMainUploader() {
	}

	public static void main(String[] arguments) throws Exception {
		Options options = Options.parse(arguments);
		Map<String, ClassFile> currentClasses = new LinkedHashMap<>();
		collectClasses("dev", options.devRoots(), currentClasses);
		collectClasses("production", List.of(options.productionRoot()), currentClasses);

		Properties previousHashes = loadHashes(options.stateFile());
		Map<String, ClassFile> changedDevClasses = changedClasses("dev", currentClasses, previousHashes);
		Map<String, ClassFile> changedProductionClasses = changedClasses("production", currentClasses, previousHashes);

		if (previousHashes.isEmpty()) {
			writeHashes(options.stateFile(), currentClasses);
			System.out.printf("HotSwap baseline recorded for %d main-mod classes.%n", currentClasses.size());
			return;
		}

		int connectedClients = 0;
		if (options.devPort() != null && !changedDevClasses.isEmpty()) {
			connectedClients += reloadOnPorts(List.of(options.devPort()), changedDevClasses);
		}
		if (!changedProductionClasses.isEmpty()) {
			connectedClients += reloadOnPorts(options.productionPorts(), changedProductionClasses);
		}

		var changedClassNames = new java.util.HashSet<>(changedDevClasses.keySet());
		changedClassNames.addAll(changedProductionClasses.keySet());
		int changedCount = changedClassNames.size();
		if (changedCount == 0) {
			writeHashes(options.stateFile(), currentClasses);
			System.out.println("HotSwap found no changed main-mod classes.");
		}
		else if (connectedClients == 0) {
			System.out.printf(
				"HotSwap prepared %d changed classes. No supported client is available, so the next run will retry.%n",
				changedCount
			);
		}
		else {
			writeHashes(options.stateFile(), currentClasses);
		}
	}

	private static int reloadOnPorts(List<Integer> ports, Map<String, ClassFile> changedClasses) throws Exception {
		int connectedClients = 0;
		for (int port : ports) {
			VirtualMachine virtualMachine = attach(port);
			if (virtualMachine == null) {
				continue;
			}
			connectedClients++;
			try {
				Map<ReferenceType, byte[]> definitions = new LinkedHashMap<>();
				List<String> loadedClassNames = new ArrayList<>();
				for (ClassFile classFile : changedClasses.values()) {
					List<ReferenceType> referenceTypes = virtualMachine.classesByName(classFile.className());
					for (ReferenceType referenceType : referenceTypes) {
						definitions.put(referenceType, Files.readAllBytes(classFile.path()));
					}
					if (!referenceTypes.isEmpty()) {
						loadedClassNames.add(classFile.className());
					}
				}
				if (definitions.isEmpty()) {
					System.out.printf("HotSwap found no changed loaded classes on JDWP port %d.%n", port);
					continue;
				}
				if (!virtualMachine.canRedefineClasses()) {
					throw new IllegalStateException("The client on JDWP port " + port + " cannot redefine classes.");
				}
				virtualMachine.redefineClasses(definitions);
				loadedClassNames.sort(String::compareTo);
				System.out.printf(
					"HotSwap reloaded %d main-mod classes on JDWP port %d: %s%n",
					loadedClassNames.size(),
					port,
					String.join(", ", loadedClassNames)
				);
			}
			finally {
				virtualMachine.dispose();
			}
		}
		return connectedClients;
	}

	private static VirtualMachine attach(int port) throws IOException, IllegalConnectorArgumentsException {
		AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
			.filter(candidate -> "com.sun.jdi.SocketAttach".equals(candidate.name()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("The JDK does not provide the JDI socket connector."));
		Map<String, Connector.Argument> arguments = connector.defaultArguments();
		arguments.get("hostname").setValue("127.0.0.1");
		arguments.get("port").setValue(Integer.toString(port));
		Connector.Argument timeout = arguments.get("timeout");
		if (timeout != null) {
			timeout.setValue("1000");
		}
		try {
			return connector.attach(arguments);
		}
		catch (IOException exception) {
			return null;
		}
	}

	private static void collectClasses(
		String profile,
		List<Path> roots,
		Map<String, ClassFile> classes
	) throws IOException, NoSuchAlgorithmException {
		for (Path root : roots) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (var paths = Files.walk(root)) {
				for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
					String relativePath = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
					if (!relativePath.startsWith(AIRICRAFT_PREFIX) || !relativePath.endsWith(CLASS_SUFFIX)) {
						continue;
					}
					String key = profile + "/" + relativePath;
					ClassFile classFile = new ClassFile(path, className(relativePath), sha512(path));
					ClassFile previous = classes.putIfAbsent(key, classFile);
					if (previous != null && !previous.sha512().equals(classFile.sha512())) {
						throw new IllegalStateException("Different class files use the same HotSwap path: " + relativePath);
					}
				}
			}
		}
	}

	private static Map<String, ClassFile> changedClasses(
		String profile,
		Map<String, ClassFile> currentClasses,
		Properties previousHashes
	) {
		Map<String, ClassFile> changed = new LinkedHashMap<>();
		String prefix = profile + "/";
		currentClasses.forEach((key, classFile) -> {
			if (key.startsWith(prefix) && !classFile.sha512().equals(previousHashes.getProperty(key))) {
				changed.put(classFile.className(), classFile);
			}
		});
		return changed;
	}

	private static Properties loadHashes(Path stateFile) throws IOException {
		Properties properties = new Properties();
		if (Files.isRegularFile(stateFile)) {
			try (InputStream input = Files.newInputStream(stateFile)) {
				properties.load(input);
			}
		}
		return properties;
	}

	private static void writeHashes(Path stateFile, Map<String, ClassFile> classes) throws IOException {
		Properties properties = new Properties();
		classes.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> properties.setProperty(entry.getKey(), entry.getValue().sha512()));
		Files.createDirectories(stateFile.getParent());
		Path temporary = Files.createTempFile(stateFile.getParent(), stateFile.getFileName().toString(), ".tmp");
		try {
			try (OutputStream output = Files.newOutputStream(temporary)) {
				properties.store(output, "Airicraft HotSwap class hashes");
			}
			try {
				Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String className(String relativePath) {
		return relativePath.substring(0, relativePath.length() - CLASS_SUFFIX.length()).replace('/', '.');
	}

	private static String sha512(Path path) throws IOException, NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-512");
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[1024 * 1024];
			int count;
			while ((count = input.read(buffer)) >= 0) {
				if (count > 0) {
					digest.update(buffer, 0, count);
				}
			}
		}
		return java.util.HexFormat.of().formatHex(digest.digest());
	}

	private record ClassFile(Path path, String className, String sha512) {
	}

	private record Options(
		Path stateFile,
		List<Path> devRoots,
		Path productionRoot,
		Integer devPort,
		List<Integer> productionPorts
	) {
		private static Options parse(String[] arguments) {
			Map<String, List<String>> values = new HashMap<>();
			for (int index = 0; index < arguments.length; index += 2) {
				if (index + 1 >= arguments.length || !arguments[index].startsWith("--")) {
					throw new IllegalArgumentException("HotSwap uploader arguments must use --name value pairs.");
				}
				values.computeIfAbsent(arguments[index], ignored -> new ArrayList<>()).add(arguments[index + 1]);
			}
			return new Options(
				Path.of(single(values, "--state")),
				values.getOrDefault("--dev-root", List.of()).stream().map(Path::of).toList(),
				Path.of(single(values, "--production-root")),
				values.containsKey("--dev-port") ? Integer.parseInt(single(values, "--dev-port")) : null,
				values.getOrDefault("--production-port", List.of()).stream().map(Integer::parseInt).toList()
			);
		}

		private static String single(Map<String, List<String>> values, String name) {
			List<String> matches = values.getOrDefault(name, List.of());
			if (matches.size() != 1) {
				throw new IllegalArgumentException("HotSwap uploader needs exactly one " + name + " argument.");
			}
			return matches.getFirst();
		}
	}
}
