# @lihbr/capacitor-camera2

[![npm version][npm-version-src]][npm-version-href]
[![npm downloads][npm-downloads-src]][npm-downloads-href]
[![Bundle][bundle-src]][bundle-href]
[![Conventional Commits][conventional-commits-src]][conventional-commits-href]
[![License][license-src]][license-href]

Capacitor plugin for `android.hardware.camera2` ***inspired from and based on*** [`@capacitor-community/camera-preview`](capacitor-community-camera-preview) all made by an Android amateur.

## Install

```bash
npm install @lihbr/capacitor-camera2
npx cap sync
```

## API

<docgen-index>

* [`start(...)`](#start)
* [`stop()`](#stop)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### start(...)

```typescript
start(options: Camera2Options) => any
```

| Param         | Type                                                      |
| ------------- | --------------------------------------------------------- |
| **`options`** | <code><a href="#camera2options">Camera2Options</a></code> |

**Returns:** <code>any</code>

--------------------


### stop()

```typescript
stop() => any
```

**Returns:** <code>any</code>

--------------------


### Type Aliases


#### Camera2Options

<code>{ x?: number; y?: number; width?: number; height?: number; paddingBottom?: number; toBack?: boolean; lockAndroidOrientation?: boolean; }</code>

</docgen-api>

## Contributing

Whether you're helping me fix bugs, improve the docs, or spread the word, I'd love to have you as a contributor!

**Asking a question**: [Open a new topic][repo-question] on GitHub Discussions explaining what you want to achieve / your question. I'll try to get back to you shortly.

**Reporting a bug**: [Open an issue][repo-bug-report] explaining your application's setup and the bug you're encountering.

**Suggesting an improvement**: [Open an issue][repo-feature-request] explaining your improvement or feature so we can discuss and learn more.

**Submitting code changes**: For small fixes, feel free to [open a PR][repo-pull-requests] with a description of your changes. For large changes, please first [open an issue][repo-feature-request] so we can discuss if and how the changes should be implemented.

## License

[MIT][license] License © 2024-PRESENT [Lucie Haberer][lihbr-github]

<!-- Links -->
[capacitor-community-camera-preview]: https://github.com/capacitor-community/camera-preview
[license]: ./LICENSE
[lihbr-github]: https://github.com/lihbr

<!-- Contributing -->

[repo-question]: https://github.com/lihbr/capacitor-camera2/discussions
[repo-bug-report]: https://github.com/lihbr/capacitor-camera2/issues/new?assignees=&labels=bug&template=bug_report.md&title=
[repo-feature-request]: https://github.com/lihbr/capacitor-camera2/issues/new?assignees=&labels=enhancement&template=feature_request.md&title=
[repo-pull-requests]: https://github.com/lihbr/capacitor-camera2/pulls

<!-- Badges -->

[npm-version-src]: https://img.shields.io/npm/v/@lihbr/capacitor-camera2?style=flat&colorA=131010&colorB=54669c
[npm-version-href]: https://npmjs.com/package/@lihbr/capacitor-camera2
[npm-downloads-src]: https://img.shields.io/npm/dm/@lihbr/capacitor-camera2?style=flat&colorA=131010&colorB=a54a5e
[npm-downloads-href]: https://npmjs.com/package/@lihbr/capacitor-camera2
[bundle-src]: https://img.shields.io/bundlephobia/minzip/@lihbr/capacitor-camera2?style=flat&colorA=131010&colorB=e84311&label=minzip
[bundle-href]: https://bundlephobia.com/result?p=@lihbr/capacitor-camera2
[conventional-commits-src]: https://img.shields.io/badge/Conventional%20Commits-1.0.0-%23FE5196?style=flat&colorA=131010&colorB=f27602&logo=conventionalcommits&logoColor=faf1f1
[conventional-commits-href]: https://conventionalcommits.org
[license-src]: https://img.shields.io/github/license/lihbr/capacitor-camera2.svg?style=flat&colorA=131010&colorB=759f53
[license-href]: https://github.com/lihbr/capacitor-camera2/blob/main/LICENSE