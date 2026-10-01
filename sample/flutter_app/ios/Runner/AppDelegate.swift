import Flutter
import UIKit

@UIApplicationMain
@objc class AppDelegate: FlutterAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    GeneratedPluginRegistrant.register(with: self)
    // Native side of `spock.sample/native` in lib/fixtures/errors.dart.
    if let controller = window?.rootViewController as? FlutterViewController {
      FlutterMethodChannel(name: "spock.sample/native", binaryMessenger: controller.binaryMessenger)
        .setMethodCallHandler { call, result in
          switch call.method {
          case "platformError":
            result(FlutterError(code: "SAMPLE_ERROR", message: "Sample PlatformException from iOS", details: nil))
          case "crash":
            fatalError("Sample native crash from the Flutter fixture app")
          default:
            result(FlutterMethodNotImplemented)
          }
        }
    }
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}
