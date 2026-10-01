package spock.adb.spock_flutter_sample

import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.IOException

/** Native side of `spock.sample/native` in lib/fixtures/errors.dart. */
class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "spock.sample/native").setMethodCallHandler { call, result ->
            when (call.method) {
                "platformError" -> result.error("SAMPLE_ERROR", "Sample PlatformException from Android", null)
                // MethodChannel catches only a RuntimeException thrown synchronously here: it logs
                // "Failed to handle method call" and replies PlatformException(error, …); the app
                // keeps running.
                "throwInHandler" -> throw IllegalStateException("Sample exception thrown in a channel handler")
                // A checked exception gets past MethodChannel to DartMessenger, which logs "Uncaught
                // exception in binary message listener" and replies empty: Dart sees a
                // MissingPluginException although the handler exists.
                "throwChecked" -> throw IOException("Sample checked exception thrown in a channel handler")
                // TODO() throws NotImplementedError, a java.lang.Error: nothing catches it and the
                // app crashes.
                "throwTodo" -> TODO("Sample")
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
