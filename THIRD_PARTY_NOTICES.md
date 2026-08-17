# Third-Party Notices

This application bundles or distributes the following third-party components.
Each component is used under its own license; their copyrights and license
texts are reproduced here (or linked) in accordance with the respective
licenses. This project itself is licensed under the MIT License (see
[LICENSE](./LICENSE)).

## Bundled runtimes and libraries

| Component | Version | Purpose | License |
| --- | --- | --- | --- |
| Node.js (Termux build, `libnode.so` + bundled `.so` libs) | 24 LTS | Embedded JavaScript runtime | [Node.js license (MIT-style)](https://raw.githubusercontent.com/nodejs/node/master/LICENSE) |
| npm | 11.x | Package manager used to install `@deepseek-ai/dsh` on first launch | [Artistic-2.0 + MIT](https://docs.npmjs.com/policies/npm-license) |
| `@deepseek-ai/dsh` (DeepSeek Harness CLI) | 0.1.0-rc.6 | The dsh server the app boots (`dsh web`) | [MIT](https://github.com/deepseek-ai/deepseek-harness/blob/main/LICENSE) |
| `@mmmbuto/node-pty-android-arm64` (node-pty Android fork) | 1.1.2 | PTY terminal support on arm64 | [MIT](app/src/main/assets/bootstrap/node-pty-android/LICENSE) |

The Node.js runtime is built from the [Termux](https://termux.dev) package
repository (`nodejs-lts` and its dependencies). Termux packages are built from
upstream sources; see the [Node.js LICENSE](https://raw.githubusercontent.com/nodejs/node/master/LICENSE)
for the Node.js copyright notice.

## Notes

- The `sharp` and `@deepseek-ai/dsh-sandbox-windows-acl` npm packages are
  replaced at install time by Android compatibility stubs shipped in
  `app/src/main/assets/bootstrap/bootstrap.js`; their licenses remain those of
  the original packages ([sharp: Apache-2.0](https://github.com/lovell/sharp/blob/main/LICENSE),
  `dsh-sandbox-windows-acl`: part of `@deepseek-ai/dsh`, MIT).
- No component requires copyleft obligations on this project's code.
