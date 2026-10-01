package spock.adb.spock_flutter_sample

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/** Native side of `spock.sample/native` in lib/fixtures/errors.dart. */
class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "spock.sample/native").setMethodCallHandler { call, result ->
            when (call.method) {
                "platformError" -> result.error("SAMPLE_ERROR", "Sample PlatformException from Android", null)
                "crash" -> throw IllegalStateException("Sample native crash from the Flutter fixture app")
                else -> result.notImplemented()
            }
        }
    }
}
