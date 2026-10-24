No real GPay / Paytm notification samples yet. Add `gpay_<n>.txt` / `paytm_<n>.txt`
(first line = notification title, rest = text) with a matching `.expected.json`;
`FixtureParserTest` picks them up automatically. Until then the notification
parsers are `// UNVERIFIED FORMAT` and covered by synthetic cases in `NotifParserTest`.
