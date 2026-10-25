import SwiftUI

@main
public struct ONTWiFiConfiguratorApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var viewModel = MainViewModel(
        startBackgroundLoops: ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil
    )

    public init() {}

    public var body: some Scene {
        WindowGroup {
            Group {
                if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil {
                    Text("ONTWiFiConfigurator Unit Test Host")
                } else {
                    SubscriberListView(viewModel: viewModel)
                        .privacySensitive()
                        .blur(radius: (scenePhase == .active || ProcessInfo.processInfo.arguments.contains("--auto-load-demo")) ? 0 : 12)
                        .onAppear {
                            if ProcessInfo.processInfo.arguments.contains("--auto-load-demo") && viewModel.allRecords.isEmpty {
                                viewModel.loadDemoExcelFromBundle()
                            }
                        }
                }
            }
            .onChange(of: scenePhase) { newPhase in
                if newPhase == .active {
                    viewModel.onScenePhaseActive()
                } else {
                    viewModel.onScenePhaseBackground()
                }
            }
        }
    }
}
