// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NativeMicCapacitor",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "NativeMicCapacitor",
            targets: ["NativeMicPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0"),
        .package(url: "https://github.com/stasel/WebRTC.git", from: "137.0.0")
    ],
    targets: [
        .target(
            name: "NativeMicPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "WebRTC", package: "WebRTC")
            ],
            path: "ios/Sources/NativeMicPlugin"),
        .testTarget(
            name: "NativeMicPluginTests",
            dependencies: ["NativeMicPlugin"],
            path: "ios/Tests/NativeMicPluginTests")
    ]
)
