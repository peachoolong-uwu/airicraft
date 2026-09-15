import test from 'node:test';
import assert from 'node:assert/strict';
import { ContainerTransfer } from '../src/os/container-transfer.mjs';
import { NativeContainer } from './fixtures/native-container.mjs';
import { itemResource, observeInventory } from '../src/os/item-observation.mjs';

test('carried stock is component-aware and missing native inventory remains unknown', async () => {
  const native = new NativeContainer({ itemId: 'minecraft:wheat' });
  native.playerQuantity = 3;
  const frame = (await native.call('os_observe')).frame;
  const wheat = itemResource('player', 'minecraft:wheat', ''), carrot = itemResource('player', 'minecraft:carrot', '');
  assert.deepEqual(observeInventory(frame, [wheat, carrot]), { [wheat]: 3, [carrot]: 0 });
  frame.facts.inventory.slots[0].variant = 'different';
  const other = itemResource('player', 'minecraft:wheat', 'different');
  assert.deepEqual(observeInventory(frame, [wheat, carrot]), { [wheat]: 0, [carrot]: 0, [other]: 3 });
  frame.facts.inventory.available = false;
  assert.deepEqual(observeInventory(frame, [wheat, carrot]), {});
});

test('a bound container projects its full observed stock and capacity without preparing an effect', async () => {
  const native = new NativeContainer({ itemId: 'minecraft:wheat' });
  const transfer = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
  const frame = (await native.call('os_observe')).frame;
  const wheat = transfer.resource('container', 'minecraft:wheat', ''), carrot = transfer.resource('container', 'minecraft:carrot', '');
  const seen = transfer.observe(frame, [wheat, carrot]);
  assert.equal(seen.stocks[wheat], 4);
  assert.equal(seen.stocks[carrot], 0);
  assert.equal(seen.capacities[transfer.resource('player', 'minecraft:wheat', '')], 64);
  assert.deepEqual(seen.targets, ['container:home']);
  frame.facts.window.open = false;
  const closed = transfer.observe(frame, [wheat, carrot]);
  assert.equal(closed.stocks[wheat], undefined);
  assert.deepEqual(closed.targets, []);
  assert.equal(native.submissions, 0);
});

test('incomplete or duplicate slot coverage cannot establish zero stock', async () => {
  const native = new NativeContainer(), frame = (await native.call('os_observe')).frame;
  frame.facts.inventory.slots.pop();
  assert.throws(() => observeInventory(frame, []), /invalid_inventory_observation/);
  frame.facts.inventory.slots.push(frame.facts.inventory.slots[0]);
  assert.throws(() => observeInventory(frame, []), /invalid_inventory_observation/);
});
