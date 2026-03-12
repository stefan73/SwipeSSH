# Third-Party Notices

This project includes vendored source code derived from the Termux terminal libraries:

- `terminal-emulator`
- `terminal-view`

Local copies are maintained under:

- `app/src/main/java/com/termux/terminal/`
- `app/src/main/java/com/termux/view/`

These terminal-library components are used to provide terminal emulation and rendering inside the Android app.

This project also depends on third-party libraries that are not vendored into the repository:

- `sshj` for SSH transport
- `bcprov-jdk18on` from Bouncy Castle for cryptographic provider support

## License

The vendored terminal-library code is used under the Apache License, Version 2.0, as documented by the upstream Termux project for the `terminal-emulator` and `terminal-view` libraries.

The `sshj` dependency is used under the Apache License, Version 2.0.

The Bouncy Castle dependency is used under its upstream permissive license terms distributed with that library.

See the upstream license snapshot included in this repository for reference:

- `third_party/termux/LICENSE.md`

This project does not vendor the Termux app-layer code or the `termux-shared` library.

