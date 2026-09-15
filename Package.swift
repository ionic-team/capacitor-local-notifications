// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorLocalNotifications",
    platforms: [.iOS(.v16)],
    products: [
        .library(
            name: "CapacitorLocalNotifications",
            targets: ["LocalNotificationsPlugin"])
    ],
    dependencies: [
        // TODO: change this after capacitor 9 release
        .package(url: "https://github.com/ionic-team/capacitor", branch: "next")
    ],
    targets: [
        .target(
            name: "LocalNotificationsPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "Capacitor"),
            ],
            path: "ios/Sources/LocalNotificationsPlugin"),
        .testTarget(
            name: "LocalNotificationsPluginTests",
            dependencies: ["LocalNotificationsPlugin"],
            path: "ios/Tests/LocalNotificationsPluginTests")
    ]
)
