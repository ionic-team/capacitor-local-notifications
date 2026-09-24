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
        .package(url: "https://github.com/ionic-team/capacitor", exact: "9.0.0-alpha.7")
    ],
    targets: [
        .target(
            name: "LocalNotificationsPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor"),
            ],
            path: "ios/Sources/LocalNotificationsPlugin"),
        .testTarget(
            name: "LocalNotificationsPluginTests",
            dependencies: ["LocalNotificationsPlugin"],
            path: "ios/Tests/LocalNotificationsPluginTests")
    ]
)
