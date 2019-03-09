package rtc

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_signalling.*
import rtc.web.just.BuildConfig
import rtc.web.just.R
import java.net.*
import java.util.concurrent.TimeUnit

class PartnerDetectorActivity : AppCompatActivity() {
    private var broadcastDisposable: Disposable? = null
    private var broadcastListenerDisposable: Disposable? = null
    private var socketDisposable: Disposable? = null
    private var socketListenerDisposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signalling)

        fun onClick(text: CharSequence) {
            phoneA.isEnabled = false
            phoneB.isEnabled = false
            message.text = String.format(getString(R.string.waiting_for), text)
        }

        phoneA.setOnClickListener {
            onClick(phoneB.text)
            broadcast()
            socketListener()
        }

        phoneB.setOnClickListener {
            onClick(phoneA.text)
            broadcastListener()
        }
    }

    override fun onDestroy() {
        rxDispose()
        super.onDestroy()
    }

    private fun broadcast() {
        val action = Completable.fromAction {
            var broadcast: InetAddress? = null
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in networkInterfaces) {
                val interfaceAddresses = networkInterface.interfaceAddresses
                val interfaceAddress = interfaceAddresses.firstOrNull { it.broadcast != null }
                if (interfaceAddress != null) {
                    broadcast = interfaceAddress.broadcast
                    break
                }
            }

            DatagramSocket(null).use {
                it.broadcast = true
                it.bind(InetSocketAddress(50100))
                val packet = BuildConfig.APPLICATION_ID.toByteArray()
                it.send(DatagramPacket(packet, packet.size, broadcast, 50100))
            }
        }

        broadcastDisposable = action
                .delay(2, TimeUnit.SECONDS)
                .repeat(Long.MAX_VALUE)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({}, this::onError)
    }

    private fun broadcastListener() {
        val single = Single.fromCallable<String> {
            DatagramSocket(null).use {
                it.soTimeout = 5 * 1000
                it.reuseAddress = true
                it.bind(InetSocketAddress(50100))

                val expected = BuildConfig.APPLICATION_ID
                val datagramPacket = DatagramPacket(ByteArray(expected.length), expected.length)

                do {
                    val canContinue = try {
                        it.receive(datagramPacket)
                        val detected = String(datagramPacket.data, 0, datagramPacket.length)
                        detected != expected
                    } catch (e: SocketTimeoutException) {
                        !broadcastListenerDisposable!!.isDisposed
                    }
                } while (canContinue)

                return@use datagramPacket.address?.hostAddress ?: ""
            }
        }

        broadcastListenerDisposable = single
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ ip -> socket(ip) }, this::onError)
    }

    private fun socket(ip: String) {
        val action = Completable.fromAction {
            Socket().use { socket ->
                val socketAddress = InetSocketAddress(ip, 50101)
                socket.soTimeout = 5 * 1000
                socket.connect(socketAddress, 5 * 1000)
            }
        }

        socketDisposable = action
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({done(ip, false)}, this::onError)
    }

    private fun socketListener() {
        val observable = Observable.create<String> { emitter ->
            ServerSocket().use { serverSocket ->
                serverSocket.soTimeout = 5 * 1000
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress(50101))

                while (!emitter.isDisposed) {
                    try {
                        serverSocket.accept().use { socket ->
                            emitter.onNext(socket.inetAddress.hostAddress)
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        socketListenerDisposable = observable
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ ip -> done(ip, true) }, this::onError)
    }

    private fun done(partnerIp: String, initiator: Boolean) {
        val intent = Intent(this, WebRTCActivity::class.java)
        intent.putExtra("partnerIp", partnerIp)
        intent.putExtra("isInitiator", initiator)
        startActivity(intent)
        finish()
    }

    private fun onError(error: Throwable) {
        error.printStackTrace()

        rxDispose()

        phoneA.isEnabled = true
        phoneB.isEnabled = true
        message.text = ""
        Toast.makeText(this, error.message.toString(), Toast.LENGTH_SHORT).show()
    }

    private fun rxDispose() {
        broadcastDisposable?.dispose()
        broadcastListenerDisposable?.dispose()
        socketDisposable?.dispose()
        socketListenerDisposable?.dispose()
    }
}