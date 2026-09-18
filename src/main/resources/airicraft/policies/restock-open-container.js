function* main(policy, input) {
  let observed = yield policy.observeContainer();
  const items = [];
  for (const [itemId, target] of Object.entries(input.stock)) {
    if (!Number.isInteger(target) || target < 0 || target > 2304)
      throw Error('Invalid stock target for ' + itemId);
    const missing = Math.max(0, target - (observed.inventory[itemId] || 0));
    if (missing > (observed.container[itemId] || 0))
      return {restocked: false, reason: 'insufficient_stock', itemId, missing};
    if (missing > 0) items.push({itemId, quantity: missing});
  }
  if (items.length > 0)
    observed = yield policy.withdraw(observed.syncId, items);
  yield policy.closeContainer(observed.syncId);
  return {restocked: true, transferred: items, inventory: observed.inventory};
}
