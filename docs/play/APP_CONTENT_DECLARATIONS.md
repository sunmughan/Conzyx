# Play Console app-content worksheet

Use these answers as a submission worksheet and revalidate them for the exact release.

| Declaration | Current answer | Notes |
| --- | --- | --- |
| Ads | No | No ad SDKs or ad content |
| Target audience | Adults / 18+ | Developer tool; not designed for children |
| News app | No | Not a news or magazine product |
| Health app | No | No health features |
| Financial features | No | No financial products or transactions |
| Government app | No | Independent developer tool |
| COVID-19 features | No | None |
| Data safety | Yes: user-selected AI providers, plus Firebase Crashlytics, Messaging, and Authentication in the online release | Use `DATA_SAFETY.md` |
| App access | Restricted AI functionality | Provide a temporary provider credential and setup instructions privately in Play Console |
| Account deletion | Required | Accounts are optional (Firebase Authentication sign-in with a guest mode). In-app account deletion exists (AuthRepository.deleteAccount). Play Console additionally requires a public web URL where users can request account deletion; that URL still has to be published and entered in the Console |
| Content rating | Utility/developer tool with user-generated AI output | Complete the IARC questionnaire honestly; AI may return open-ended text/code |
| Privacy policy | Required | Public `PRIVACY.md` URL in listing and in-app Settings |
| Foreground service | Required | Declare all three `specialUse` services (setup, execution, keep-alive) and provide a demonstration video; see `FOREGROUND_SERVICE_DECLARATION.md` |
| Billing | Not used | Add Play Billing before selling digital subscriptions or features in-app |

## Store listing contact

- Website: `https://github.com/sunmughan/Conzyx`
- Support: `https://github.com/sunmughan/Conzyx/issues`
- Email: provide a dedicated public support address in Play Console. Do not reuse a private signing or account email without explicit approval.
