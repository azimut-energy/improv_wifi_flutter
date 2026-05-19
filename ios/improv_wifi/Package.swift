// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "improv_wifi",
    platforms: [
        .iOS("15.0")
    ],
    products: [
        .library(name: "improv-wifi", targets: ["improv_wifi"])
    ],
    dependencies: [
        .package(url: "https://github.com/improv-wifi/sdk-iOS.git", from: "0.0.6"),
    ],
    targets: [
        .target(
            name: "improv_wifi",
            dependencies: [
                .product(name: "ImproviOS", package: "sdk-iOS"),
            ],
            resources: []
        )
    ]
)
