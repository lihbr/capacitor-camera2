// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "LihbrCapacitorCamera2",
    platforms: [.iOS(.v13)],
    products: [
        .library(
            name: "LihbrCapacitorCamera2",
            targets: ["Camera2Plugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", branch: "main")
    ],
    targets: [
        .target(
            name: "Camera2Plugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/Camera2Plugin"),
        .testTarget(
            name: "Camera2PluginTests",
            dependencies: ["Camera2Plugin"],
            path: "ios/Tests/Camera2PluginTests")
    ]
)