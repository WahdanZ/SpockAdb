package spock.adb.spock_flutter_sample

import android.os.Handler
import android.os.Looper
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
                // Flutter's channel dispatcher catches this and replies PlatformException(error, …);
                // the app keeps running.
                "throwInHandler" -> throw IllegalStateException("Sample exception thrown in a channel handler")
                // A real crash: thrown outside the channel dispatcher, so it is an uncaught
                // exception on the main thread and kills the process.
                "crash" -> {
                    result.success(null)
                    Handler(Looper.getMainLooper()).post {
                        throw IllegalStateException("Sample native crash from the Flutter fixture app")
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
}
