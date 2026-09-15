import test from 'node:test';
import assert from 'node:assert/strict';
import { ActivityCoordinator } from '../src/os/activities.mjs';
import { ContainerTransfer } from '../src/os/container-transfer.mjs';

test('supply installation rejects dependency cycles with the path before any activity can be admitted', () => {
  const chest = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
  const operations = {
    chest,
    rod: { supplyDependencies: ['sticks', 'chest'] },
    sticks: { supplyDependencies: ['planks'] },
    planks: { supplyDependencies: ['chest'] }
  };
  assert.doesNotThrow(() => new ActivityCoordinator({ operations }));
  operations.planks.supplyDependencies = ['rod'];
  assert.throws(() => new ActivityCoordinator({ operations }), error => {
    assert.equal(error.message, 'supply_dependency_cycle');
    assert.deepEqual(error.path, ['rod', 'sticks', 'planks', 'rod']);
    return true;
  });
  operations.planks.supplyDependencies = ['missing'];
  assert.throws(() => new ActivityCoordinator({ operations }), error => {
    assert.equal(error.message, 'supply_dependency_missing');
    assert.deepEqual(error.path, ['planks', 'missing']);
    return true;
  });
  assert.throws(() => new ActivityCoordinator({ operations: { self: { supplyDependencies: ['self'] } } }), /supply_dependency_cycle/);
});

test('supply declarations bound their size and reject malformed dependencies', () => {
  assert.throws(() => new ActivityCoordinator({ operations: { transfer: { supplyDependencies: 'chest' } } }), /invalid_supply_dependencies/);
  const operations = Object.fromEntries(Array.from({ length: 33 }, (_, index) => [`method_${index}`, {}]));
  assert.throws(() => new ActivityCoordinator({ operations }), /supply_rule_capacity/);
});
