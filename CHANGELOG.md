# Changelog

## [1.3.4](https://github.com/zentrum-lexikographie/dwds-livestream/compare/v1.3.3...v1.3.4) (2025-07-08)


### Bug Fixes

* Adjust collector source URL to new deployment @ www.dwds.de ([9f450c8](https://github.com/zentrum-lexikographie/dwds-livestream/commit/9f450c8a1737d5fafec71761119df56c79ef84f4))

## [1.3.3](https://github.com/zentrum-lexikographie/dwds-livestream/compare/v1.3.2...v1.3.3) (2025-05-04)


### Bug Fixes

* **Collector:** retry collection when event stream is exhausted/closed ([22eaadd](https://github.com/zentrum-lexikographie/dwds-livestream/commit/22eaaddf822499e719d41beb3745ad8c270ba0ec))

## [1.3.2](https://github.com/zentrum-lexikographie/dwds-livestream/compare/v1.3.1...v1.3.2) (2025-02-02)


### Bug Fixes

* **Collector:** Filter lemmata by database's maximum column length ([9ef87c9](https://github.com/zentrum-lexikographie/dwds-livestream/commit/9ef87c98f5863d54eda67c13183dcd73a76e7211))

## [1.3.1](https://github.com/zentrum-lexikographie/dwds-livestream/compare/v1.3.0...v1.3.1) (2025-01-03)


### Bug Fixes

* **Docker Build:** Download dependencies for server and collector mode ([20fc160](https://github.com/zentrum-lexikographie/dwds-livestream/commit/20fc160fdba1014f28f261d35311af03d0b91c23))
* **Server:** Access log tailer was initialized with path of wrong type ([041c08c](https://github.com/zentrum-lexikographie/dwds-livestream/commit/041c08c2a80a4b062af41c7821db15efb6184b57))

## [1.3.0](https://github.com/zentrum-lexikographie/dwds-livestream/compare/v1.2.0...v1.3.0) (2025-01-03)


### Features

* Add collector module, persisting events to PostgreSQL ([f42623d](https://github.com/zentrum-lexikographie/dwds-livestream/commit/f42623d438acccfd9fb818c3c3364d7ea0eb975e))
* **Visualization:** Pause data retrieval on visibility change ([5cf45b5](https://github.com/zentrum-lexikographie/dwds-livestream/commit/5cf45b59d9acc4dc68c1479e9478fd3b94490adf))

## [1.2.0](https://github.com/zentrum-lexikographie/dwds-livestream/compare/v1.1.0...v1.2.0) (2024-12-18)


### Features

* **API:** Adds HTTP headers indicating SSE content to proxies ([4b7c6b2](https://github.com/zentrum-lexikographie/dwds-livestream/commit/4b7c6b278faeff351dff754a61dc5d231eac6517))

## [1.1.0](https://github.com/zentrum-lexikographie/dwds-livestream/compare/v1.0.0...v1.1.0) (2024-12-18)


### Features

* **Continuous Integration:** publish images to internal docker registry ([e9b14da](https://github.com/zentrum-lexikographie/dwds-livestream/commit/e9b14dae4d984a438a9d7dcf1214cd58c98fb6c2))
* **Visualization:** Do not display article sources by default ([456f2c1](https://github.com/zentrum-lexikographie/dwds-livestream/commit/456f2c1628e2fd048c08d8922921b0ac9e2cb07c))

## 1.0.0 (2024-12-17)


### Miscellaneous Chores

* release v1.0.0 ([6dbceb5](https://github.com/zentrum-lexikographie/dwds-livestream/commit/6dbceb5ecd753ac6283b75fea1059da9e21f35f0))
