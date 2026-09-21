package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import java.util.Set;

/** Deliberately small advertised JSON-schema subset; unsupported constraints are rejected. */
final class ToolInputSchema {
	static void check(JsonObject schema) {
		if (!Set.of("type","properties","required","additionalProperties","description").containsAll(schema.keySet())
			|| !"object".equals(schema.get("type").getAsString()) || !schema.has("additionalProperties")
			|| !schema.get("additionalProperties").equals(new JsonPrimitive(false))) throw new IllegalArgumentException("invalid_object_schema");
		var props=schema.getAsJsonObject("properties");
		if (props.size()>32) throw new IllegalArgumentException("schema_property_limit");
		for (var e:props.entrySet()) {
			var p=e.getValue().getAsJsonObject();
			if (!Set.of("type","description","enum","minimum","maximum").containsAll(p.keySet())
				|| !Set.of("string","number","integer","boolean").contains(p.get("type").getAsString())) throw new IllegalArgumentException("unsupported_property_schema");
			for (String bound: new String[]{"minimum","maximum"}) if(p.has(bound)) {
				if (!Set.of("number","integer").contains(p.get("type").getAsString())) throw new IllegalArgumentException("numeric_bounds_only");
				p.get(bound).getAsBigDecimal();
			}
			if (p.has("enum")) for (var value:p.getAsJsonArray("enum")) validateValue(p,value);
		}
		if(schema.has("required")) for(var key:schema.getAsJsonArray("required")) if(!props.has(key.getAsString())) throw new IllegalArgumentException("unknown_required_property");
	}
	static void validate(JsonObject schema, JsonObject input) {
		if(input.toString().length()>16384) throw new IllegalArgumentException("tool_input_limit");
		var props=schema.getAsJsonObject("properties");
		if(schema.has("required")) for(var key:schema.getAsJsonArray("required")) if(!input.has(key.getAsString())) throw new IllegalArgumentException("missing_input:"+key.getAsString());
		for(var e:input.entrySet()) {
			if(!props.has(e.getKey())) throw new IllegalArgumentException("unknown_input:"+e.getKey());
			validateValue(props.getAsJsonObject(e.getKey()),e.getValue());
		}
	}
	private static void validateValue(JsonObject p, JsonElement value) {
		if(!value.isJsonPrimitive()) throw new IllegalArgumentException("primitive_input_required");
		var v=value.getAsJsonPrimitive(); String type=p.get("type").getAsString();
		boolean valid=switch(type) {case "string" -> v.isString(); case "boolean" -> v.isBoolean(); default -> v.isNumber();};
		if(!valid) throw new IllegalArgumentException("input_type:"+type);
		if(v.isNumber()) {
			var n=v.getAsBigDecimal();
			if(type.equals("integer")) n.toBigIntegerExact();
			if(p.has("minimum") && n.compareTo(p.get("minimum").getAsBigDecimal())<0 || p.has("maximum") && n.compareTo(p.get("maximum").getAsBigDecimal())>0) throw new IllegalArgumentException("input_out_of_range");
		}
		if(p.has("enum") && !p.getAsJsonArray("enum").contains(value)) throw new IllegalArgumentException("input_not_in_enum");
	}
}
