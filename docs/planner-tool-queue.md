# Planner tool queue

The embedded controller and thinking planner accept multiple tool calls per response. Calls append to the submitting role's FIFO and run sequentially on the client thread. A delegated planner has its own FIFO so its actions can run while the controller's `delegate_task` awaits its return. Only the role with decision authority dispatches calls.

For a plan A, B, C, accepting the response queues all three. Once A actually completes, B starts without waiting for another model response. A review carries A's result plus the active B and pending C. If the planner adds D, D goes behind C. `continue` adds no work and ends the decision turn; it is never required to advance the queue.

An action receipt admitting ongoing work does not complete the queue entry. The entry waits for that work ID's SUCCEEDED, FAILED, or CANCELLED state. A failure is reported and execution advances; the planner can abort and replace the remaining plan. Calls must therefore have concrete arguments supported by observations already available when queued, not invented outputs of earlier calls.

`clear_queue` is immediate, regardless of its position among calls in the response. It discards pending calls, abandons the current tool future, and cancels current foreground root work through the runtime's work cancellation paths. Replacement calls from that response wait for cancellation to complete. Cancellation cannot undo effects already completed or stop physical furnace cooking. A late result from an abandoned future cannot advance the old queue.

Results coalesce across a 250 ms quiet interval. If another read is running, the review waits for it, up to 1 second from the first buffered result. A review already in flight is not interrupted by another queue result; those results enter the next review. Safety/user events can still prompt a review. Queue dispatch continues independently of inference, including during coalescing.

Conversation history acknowledges every submitted tool call as queued, preserving complete tool-call/result pairs. Actual results replace the queued acknowledgment when the next request is constructed. Provider-managed history or a context reset receives an explicit execution-result notice if the original envelope is absent. Raw exchanges remain in recording evidence. `TOOL QUEUE` is a current snapshot, not an accumulating history of queue snapshots.

Short-term findings apply to completed observations, not queued acknowledgments. A review can summarize several completed queries and append a plan in the same response. `continue` and `clear_queue` remain available when findings are pending. This queue and its reports are session-local; there is no persistence mechanism.
