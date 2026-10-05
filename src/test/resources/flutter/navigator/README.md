# Navigator VM objects

Hand-built fixtures using the S25 object shapes, not recorded device transcripts. IDs are local
fixture references. `isolate` answers getIsolate; `objects` maps IDs to getObject replies;
`instances` maps NavigatorState class IDs to getInstances replies. Small references and each
object are kept on one line so a field change stays together in review.

The two root fixtures represent Flutter 3.22.2 and 3.47.5 (the latter adds entry/state fields).
The remaining fixtures model dialogs, transitions, pages and nested navigators pending device
validation. Unmounted states have an explicit null `_element`; an absent field is an error.
