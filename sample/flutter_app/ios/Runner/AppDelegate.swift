import Flutter
import UIKit
import UserNotifications

@UIApplicationMain
@objc class AppDelegate: FlutterAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    GeneratedPluginRegistrant.register(with: self)
    // Without a delegate, a push that arrives in the foreground (`simctl push`) shows nothing.
    UNUserNotificationCenter.current().delegate = self
    // Native side of `spock.sample/native` in lib/fixtures/errors.dart.
    if let controller = window?.rootViewController as? FlutterViewController {
      FlutterMethodChannel(name: "spock.sample/native", binaryMessenger: controller.binaryMessenger)
        .setMethodCallHandler { call, result in
          switch call.method {
          case "platformError":
            result(FlutterError(code: "SAMPLE_ERROR", message: "Sample PlatformException from iOS", details: nil))
          case "crash":
            fatalError("Sample native crash from the Flutter fixture app")
          case "throwInHandler", "throwChecked", "throwTodo":
            // Android only: these exercise how the Android embedding treats a RuntimeException, a
            // checked exception and a java.lang.Error thrown in a handler. A Swift handler cannot
            // throw, and an Objective-C exception here is not caught by Flutter, so it would be a
            // second crash fixture rather than these.
            result(FlutterMethodNotImplemented)
          default:
            result(FlutterMethodNotImplemented)
          }
        }
    }
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  /// Shows a foreground push as a banner and stores a summary under `flutter.last_push`, which
  /// shared_preferences reads as `last_push`, so the Push screen's Refresh works as on Android.
  override func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    let content = notification.request.content
    let summary = "title=\(content.title)\nbody=\(content.body)\nuserInfo=\(content.userInfo)"
    UserDefaults.standard.set(summary, forKey: "flutter.last_push")
    if #available(iOS 14.0, *) {
      completionHandler([.banner, .list, .sound])
    } else {
      completionHandler([.alert, .sound])
    }
  }
}
