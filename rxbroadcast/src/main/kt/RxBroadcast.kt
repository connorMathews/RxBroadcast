import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import rx.Observable
import rx.Subscriber
import rx.Subscription
import rx.android.schedulers.HandlerScheduler
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater

object RxBroadcast {
    /**
     * Creates a stateful [Observable] of [NetworkInfo] and changes via a
     * [BroadcastReceiver]. You probably only want to do this once per
     * application/activity/service and then share the resulting observable.
     */
    fun activeNetworkInfo(context: Context): Observable<NetworkInfo> {
        return create(BroadcastOnSubscribe(
                context,
                IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
                null,
                { context, intent -> context.cm().activeNetworkInfo }))
    }

    fun Context.cm(): ConnectivityManager {
        return this.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
    }

    internal fun <T> create(onSubscribe: BroadcastOnSubscribe<T>):
            Observable<T> {
        return Observable.create(onSubscribe)
                .subscribeOn(scheduler)
    }

    internal var scheduler: HandlerScheduler;
    init {
        val worker = HandlerThread("BroadcastReceiver")
        worker.start()
        scheduler = HandlerScheduler.from(Handler(worker.looper))
    }
}

/**
 * An anonymous [BroadcastReceiver] will be created per subscription.
 * The receiver will unregister at un-subscribe and will *not* call
 * the subscriber's onCompleted. The receiver will be registered to
 * application context to avoid inadvertent leaks.
 *
 * Can be subscribed on any scheduler and will create a looper for a thread
 * if one is not already prepared. The receiver will be registered with the
 * given thread's [Handler]. Note: creating a Looper for a Thread will tie
 * it up w/ an event loop so this is best used w/ NewThreadScheduler or
 * HandlerScheduler.
 *
 * TODO tests
 */
internal class BroadcastOnSubscribe<T>(val context: Context,
                                       val filter: IntentFilter,
                                       val permission: String?,
                                       val receive: (Context, Intent) -> T?) :
        Observable.OnSubscribe<T> {
    override fun call(subscriber: Subscriber<in T>) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!subscriber.isUnsubscribed) {
                    subscriber.onNext(receive(context, intent))
                }
            }
        }

        var newLooper = false;
        if (Looper.myLooper() == null) {
            Looper.prepare()
            newLooper = true;
        }

        val handler = Handler()

        context.applicationContext.registerReceiver(receiver, filter,
                permission, handler)

        val quitLooper = newLooper;
        subscriber.add(object : HandlerSubscription(handler) {
            override fun onUnsubscribe() {
                context.applicationContext.unregisterReceiver(receiver)
                if (quitLooper) {
                    if (Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        Looper.myLooper().quitSafely()
                    } else {
                        Looper.myLooper().quit()
                    }
                }
            }
        })

        if (newLooper) {
            Looper.loop()
        }
    }
}

// Forked from RxBinding's MainThreadSubscription.
internal abstract class HandlerSubscription(val handler: Handler) : Subscription,
        Runnable {
    @Volatile var unsubscribed: Int = 0

    companion object {
        val unsubscribedUpdater: AtomicIntegerFieldUpdater<HandlerSubscription> =
                AtomicIntegerFieldUpdater.newUpdater(
                        HandlerSubscription::class.java, "unsubscribed")
    }

    override fun isUnsubscribed(): Boolean {
        return !unsubscribed.equals(0)
    }

    override fun unsubscribe() {
        if (unsubscribedUpdater.compareAndSet(this, 0, 1)) {
            handler.post(this)
        }
    }

    override fun run() {
        onUnsubscribe()
    }

    abstract fun onUnsubscribe()
}
