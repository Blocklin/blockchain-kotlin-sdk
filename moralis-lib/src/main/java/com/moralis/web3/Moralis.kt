package com.moralis.web3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.parse.Parse
import com.parse.ParseACL
import com.parse.ParseUser
import com.parse.boltsinternal.Continuation
import com.parse.boltsinternal.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.walletconnect.Session
import org.walletconnect.nullOnThrow

typealias User = ParseUser

open class Moralis {

    companion object {
        private const val TAG = "Moralis"

        private lateinit var mCallback: Session.Callback
        private lateinit var mActivityCallback: MoralisCallback
        private val uiScope = CoroutineScope(Dispatchers.Main)
        private var mTxRequest: Long? = null

        fun initialize(appId: String, serverURL: String, applicationContext: Context) {
            Parse.initialize(
                Parse.Configuration.Builder(applicationContext)
                    .applicationId(appId)
                    .server(serverURL)
                    .build()
            )
        }

        /**
         * Signs in or up a user onto the Moralis Server.
         */
        fun authenticate(
            context: Context,
            signingMessage: String?,
            authenticationType: MoralisAuthentication = MoralisAuthentication.Ethereum,
            supportedWallets: Array<String> = emptyArray(),
            chainId: Int? = 1,
            moralisAuthCallback: (user: User?) -> Unit,
        ) {
            // TODO: use chainId and supportedWallets
            when (authenticationType) {
                MoralisAuthentication.Polkadot -> MoralisPolkadot.authenticate()
                MoralisAuthentication.Elrond -> MoralisElrond.authenticate()
                MoralisAuthentication.Ethereum -> authenticate(signingMessage, context, supportedWallets, chainId, moralisAuthCallback)
            }
        }

        private fun authenticate(
            signingMessage: String?,
            context: Context,
            supportedWallets: Array<String>,
            chainId: Int?,
            moralisAuthCallback: (user: User?) -> Unit
        ) {
            // Starts a new connection to the bridge server and waits for a wallet to connect.
            MoralisApplication.resetSession(supportedWallets, chainId)

            // If a custom signing message for the wallet signature prompt was not provided use
            // a default one.
            val data = signingMessage ?: getSigningData()

            mCallback = object : Session.Callback {
                override fun onStatus(status: Session.Status) {
                    when (status) {
                        Session.Status.Approved -> handleSessionApproved(
                            moralisAuthCallback,
                            context,
                            data
                        )
                        Session.Status.Closed -> {
                            Log.e(TAG, "onStatus Session Closed")
                            handleSessionClosed()
                        }
                        Session.Status.Connected -> {
                            requestConnectionToWallet(context)
                        }
                        Session.Status.Disconnected -> {
                            Log.e(TAG, "onStatus Session Disconnected")
                            handleSessionClosed()
                        }
                        is Session.Status.Error -> {
                            Log.e(TAG, "onStatus Error:" + status.throwable.localizedMessage)
                            // TODO
                        }
                    }
                }

                override fun onMethodCall(call: Session.MethodCall) {
                    Log.d(TAG, "onMethodCall: " + call.id())
                }
            }
            MoralisApplication.session.addCallback(mCallback)
        }

        fun onDestroy() {
            // If session is not initialized this method may be called anyways, so return.
            val session = nullOnThrow { MoralisApplication.session } ?: return

            session.removeCallback(mCallback)
        }

        /**
         * Sends an intent to the OS with the intention of opening a wallet that can handle the
         * authentication.
         */
        private fun requestConnectionToWallet(context: Context) {
            val i = Intent(Intent.ACTION_VIEW)
            i.data = Uri.parse(MoralisApplication.config.toWCUri())
            context.startActivity(i)
        }

        /**
         * Starts the signing up process after a wallet approved a connection to it.
         */
        private fun handleSessionApproved(
            moralisAuthCallback: (result: User?) -> Unit,
            context: Context,
            signingMessage: String
        ) {
            uiScope.launch {
                signUpToMoralis(moralisAuthCallback, context, signingMessage)
                Log.d(TAG, "Connected:  ${MoralisApplication.session.approvedAccounts()}")
            }
        }

        private fun handleSessionClosed() {
            MoralisApplication.session.removeCallback(mCallback)
            uiScope.launch {
                mActivityCallback.onStatus(MoralisStatus.Closed, null)
            }
        }

        private fun signUpToMoralis(
            moralisAuthCallback: (result: User?) -> Unit,
            context: Context,
            signingMessage: String
        ) {
            val accounts = MoralisApplication.session.approvedAccounts() ?: return
            val accountsLowercase = accounts.map { it.lowercase() }
            val ethAddress = accountsLowercase.first()
            val id = System.currentTimeMillis()
            Log.d(TAG, "accountsLowercase: $accountsLowercase")
            Log.d(TAG, "ethAddress: $ethAddress")
            Log.d(TAG, "id: $id")
            Log.d(TAG, "signingMessage: $signingMessage")

            // We must explicitly specify the parameters names otherwise the compiler for some
            // reason doesn't respect the order of passed parameters and may link address with message
            // and message with address.
            // TODO: for now we sent the address as message and the message as address,
            // it's strange but sending the parameters in the "right way" doesn't work,
            // neither with Metamask nor with TrustWallet.
            val signMessage = Session.MethodCall.PersonalSignMessage(
                id = id,
                message =  ethAddress,
                address = signingMessage
            )
            // TODO: maybe use Sign Typed Data v4 instead
            MoralisApplication.session.performMethodCall(signMessage) {
                handleResponse(
                    it,
                    ethAddress,
                    signingMessage,
                    accountsLowercase,
                    moralisAuthCallback
                )
            }
            this.mTxRequest = id
            // TODO: send intent anyways but with FLAG to avoid restart in case of already being
            // visible.
//            navigateToWallet(context)
        }

        private fun handleResponse(
            response: Session.MethodCall.Response,
            ethAddress: String,
            signingMessage: String,
            accountsLowercase: List<String>,
            moralisAuthCallback: (result: User?) -> Unit
        ) {
            Log.d(TAG, "handleResponse, response: ${response.id} mTxRequest=$mTxRequest")
            if (response.id == mTxRequest) {
                Log.d(TAG, "response.id == mTxRequest")
                mTxRequest = null
                uiScope.launch {
                    val signature = ((response.result as? String) ?: "Unknown response")

                    val authData = mapOf("id" to ethAddress, "signature" to signature, "data" to signingMessage)

                    val parseUserTask = User.logInWithInBackground("moralisEth", authData)
                    parseUserTask.continueWith(object : Continuation<User?, Void?> {
                        override fun then(task: Task<User?>): Void? {
                            if (task.isCancelled()) {
                                Log.d(TAG, "then: task.isCancelled()")
//                                // TODO showError()
                                return null
                            }
                            if (task.isFaulted()) {
                                Log.d(TAG, "then: ask.isFaulted()")
//                                // TODO showError()
                                return null
                            }
                            val user: User? = task.result
                            user?.acl = ParseACL(user);
                            // TODO: if (!user) throw new Error('Could not get user');
                            user?.addAllUnique("accounts", accountsLowercase)
                            user?.put("ethAddress", ethAddress);
                            Log.d(TAG, "call saveInBackground")
                            user?.saveInBackground {
                                Log.d(TAG, "user logged in.")
                                moralisAuthCallback.invoke(user)
                            }
                            return null
                        }
                    })
                }
            }
        }

        private fun navigateToWallet(context: Context) {
            val i = Intent(Intent.ACTION_VIEW)
            i.data = Uri.parse("wc:")
            context.startActivity(i)
        }

        fun onStart(callback: MoralisCallback) {
            mActivityCallback = callback
            initialSetup()
        }

        private fun initialSetup() {
            // if Application.session is not initialized then return
            val session = nullOnThrow { MoralisApplication.session } ?: return
            session.addCallback(mCallback)
            if (session.approvedAccounts() != null) {
                mActivityCallback.onStatus(MoralisStatus.Approved, session.approvedAccounts())
            }
        }

        fun logOut() {
            // Usually the user gets cached, this we could have a user logged-in and this method being
            // called without session. We simply ignore the session and log out the user.
            nullOnThrow { MoralisApplication.session }?.kill()
            User.logOut()
        }

        fun getSigningData(): String {
            return "Authentication Interface"
        }
    }

    interface MoralisCallback {
        fun onStatus(status: MoralisStatus, accounts: List<String>?)
    }

    sealed class MoralisStatus {
        object Approved : MoralisStatus()
        object Disconnected : MoralisStatus()
        object Closed : MoralisStatus()
        data class Error(val throwable: Throwable) : MoralisStatus()
    }

    sealed class MoralisAuthentication {
        object Ethereum : MoralisAuthentication()
        object Polkadot : MoralisAuthentication()
        object Elrond : MoralisAuthentication()
    }

    enum class EthereumEvents {
        CONNECT, DISCONNECT, ACCOUNTS_CHANGED, CHAIN_CHANGED
    }
}