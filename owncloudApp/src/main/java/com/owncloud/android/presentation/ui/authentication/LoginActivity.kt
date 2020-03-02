/**
 * ownCloud Android client application
 *
 * @author Bartek Przybylski
 * @author David A. Velasco
 * @author masensio
 * @author David González Verdugo
 * @author Christian Schabesberger
 * @author Shashvat Kedia
 * @author Abel García de Prada
 * Copyright (C) 2012  Bartek Przybylski
 * Copyright (C) 2020 ownCloud GmbH.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.presentation.ui.authentication

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Observer
import com.owncloud.android.MainApp
import com.owncloud.android.MainApp.Companion.accountType
import com.owncloud.android.R
import com.owncloud.android.authentication.AccountAuthenticatorActivity
import com.owncloud.android.authentication.OAuthUtils
import com.owncloud.android.domain.exceptions.NoNetworkConnectionException
import com.owncloud.android.domain.exceptions.OwncloudVersionNotSupportedException
import com.owncloud.android.domain.exceptions.ServerNotReachableException
import com.owncloud.android.domain.server.model.AuthenticationMethod
import com.owncloud.android.domain.server.model.ServerInfo
import com.owncloud.android.extensions.parseError
import com.owncloud.android.lib.common.accounts.AccountTypeUtils
import com.owncloud.android.lib.common.accounts.AccountUtils
import com.owncloud.android.lib.common.network.CertificateCombinedException
import com.owncloud.android.presentation.UIResult
import com.owncloud.android.presentation.viewmodels.authentication.OCAuthenticationViewModel
import com.owncloud.android.ui.dialog.LoadingDialog
import com.owncloud.android.ui.dialog.SslUntrustedCertDialog
import com.owncloud.android.utils.DocumentProviderUtils.Companion.notifyDocumentProviderRoots
import com.owncloud.android.utils.PreferenceUtils
import kotlinx.android.synthetic.main.account_setup.*
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationService.TokenResponseCallback
import net.openid.appauth.TokenResponse
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

class LoginActivity : AccountAuthenticatorActivity(), SslUntrustedCertDialog.OnSslUntrustedCertListener {

    private val authenticationViewModel by viewModel<OCAuthenticationViewModel>()

    private var loginAction: Byte = ACTION_CREATE
    private var authTokenType: String? = null
    private var userAccount: Account? = null
    private var serverBaseUrl: String? = null

    private var mAuthService: AuthorizationService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Protection against screen recording
        if (!MainApp.isDeveloper) {
            window.addFlags(FLAG_SECURE)
        } // else, let it go, or taking screenshots & testing will not be possible

        // Get values from intent
        loginAction = intent.getByteExtra(EXTRA_ACTION, ACTION_CREATE)
        authTokenType = intent.getStringExtra(KEY_AUTH_TOKEN_TYPE)
        userAccount = intent.getParcelableExtra(EXTRA_ACCOUNT)

        // Get values from savedInstanceState
        if (savedInstanceState == null) {
            initAuthTokenType()
        } else {
            authTokenType = savedInstanceState.getString(KEY_AUTH_TOKEN_TYPE)
        }

        // UI initialization
        setContentView(R.layout.account_setup)

        login_layout.filterTouchesWhenObscured =
            PreferenceUtils.shouldDisallowTouchesWithOtherVisibleWindows(this@LoginActivity)

        initBrandableOptionsUI()

        thumbnail.setOnClickListener { checkOcServer() }

        embeddedCheckServerButton.setOnClickListener { checkOcServer() }


        loginButton.setOnClickListener {
            if (AccountTypeUtils.getAuthTokenTypeAccessToken(accountType) == authTokenType) { // OAuth
                startOAuthorization()
            } else { // Basic
                authenticationViewModel.loginBasic(account_username.text.toString(), account_password.text.toString())
            }
        }

        // LiveData observers
        authenticationViewModel.serverInfo.observe(this, Observer { event ->
            when (event.peekContent()) {
                is UIResult.Success -> getServerInfoIsSuccess(event.peekContent())
                is UIResult.Loading -> getServerInfoIsLoading()
                is UIResult.Error -> getServerInfoIsError(event.peekContent())
            }
        })

        authenticationViewModel.loginResult.observe(this, Observer { event ->
            when (event.peekContent()) {
                is UIResult.Success -> loginIsSuccess(event.peekContent())
                is UIResult.Loading -> loginIsLoading()
                is UIResult.Error -> loginIsError(event.peekContent())
            }
        })

        authenticationViewModel.supportsOAuth2.observe(this, Observer { event ->
            updateAuthTokenTypeAndInstructions(event.peekContent())
        })
    }

    private fun initAuthTokenType() {
        authTokenType = intent.extras.getString(KEY_AUTH_TOKEN_TYPE)
        if (authTokenType == null) {
            if (userAccount != null) {
                authenticationViewModel.supportsOAuth2()
            } else { // OAuth will be the default authentication method
                authTokenType = ""
            }
        }
    }

    private fun checkOcServer() {
        val uri = hostUrlInput.text.toString().trim()
        authenticationViewModel.getServerInfo(serverUrl = uri)
    }

    private fun updateLoginButtonVisibility() {
        loginButton.run {
            isVisible = account_username.text.toString().isNotBlank() && account_password.text.toString().isNotBlank()
        }
    }

    private fun getServerInfoIsSuccess(uiResult: UIResult<ServerInfo>) {
        uiResult.getStoredData()?.run {
            serverBaseUrl = baseUrl
            hostUrlInput.run {
                setText(baseUrl)
                doAfterTextChanged {
                    //If user modifies url, reset fields and force him to check url again
                    if (authenticationViewModel.serverInfo.value == null || baseUrl != hostUrlInput.text.toString()) {
                        showOrHideBasicAuthFields(shouldBeVisible = false)
                        loginButton.isVisible = false
                        server_status_text.run {
                            text = ""
                            isVisible = false
                        }
                    }
                }
            }

            server_status_text.run {
                if (isSecureConnection) {
                    text = resources.getString(R.string.auth_secure_connection)
                    setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_lock, 0, 0, 0)
                } else {
                    text = resources.getString(R.string.auth_connection_established)
                    setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_lock_open, 0, 0, 0)
                }
                visibility = VISIBLE
            }

            when (authenticationMethod) {
                AuthenticationMethod.BASIC_HTTP_AUTH -> {
                    authTokenType = BASIC_TOKEN_TYPE
                    showOrHideBasicAuthFields(shouldBeVisible = true)
                    account_username.run {
                        doAfterTextChanged { updateLoginButtonVisibility() }
                    }
                    account_password.run {
                        doAfterTextChanged {
                            updateLoginButtonVisibility()
                            account_password_container.isPasswordVisibilityToggleEnabled =
                                !account_password.text.isNullOrEmpty()
                        }
                    }
                }

                AuthenticationMethod.BEARER_TOKEN -> {
                    showOrHideBasicAuthFields(shouldBeVisible = false)
                    authTokenType = OAUTH_TOKEN_TYPE
                    loginButton.visibility = VISIBLE
                }

                else -> {
                    server_status_text.run {
                        text = resources.getString(R.string.auth_unsupported_auth_method)
                        setCompoundDrawablesWithIntrinsicBounds(R.drawable.common_error, 0, 0, 0)
                        visibility = VISIBLE
                    }
                }
            }
        }
    }

    private fun getServerInfoIsLoading() {
        server_status_text.run {
            text = resources.getString(R.string.auth_testing_connection)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.progress_small, 0, 0, 0)
            visibility = VISIBLE
        }
    }

    private fun getServerInfoIsError(uiResult: UIResult<ServerInfo>) {
        when (uiResult.getThrowableOrNull()) {
            is CertificateCombinedException ->
                showUntrustedCertDialog(uiResult.getThrowableOrNull() as CertificateCombinedException)
            is OwncloudVersionNotSupportedException -> server_status_text.run {
                text = getString(R.string.server_not_supported)
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.common_error, 0, 0, 0)
            }
            is NoNetworkConnectionException -> server_status_text.run {
                text = getString(R.string.error_no_network_connection)
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.no_network, 0, 0, 0)
            }
            else -> server_status_text.run {
                text = uiResult.getThrowableOrNull()?.parseError("", resources, true)
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.common_error, 0, 0, 0)
            }
        }
        server_status_text.isVisible = true
        showOrHideBasicAuthFields(shouldBeVisible = false)
    }

    private fun loginIsSuccess(uiResult: UIResult<String>) {
        dismissLoadingDialog()

        // Return result to account authenticator, multiaccount does not work without this
        val accountName = uiResult.getStoredData()
        val intent = Intent()
        intent.putExtra(AccountManager.KEY_ACCOUNT_TYPE, accountType)
        intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, accountName)
        setAccountAuthenticatorResult(intent.extras)
        setResult(Activity.RESULT_OK, intent)

        notifyDocumentProviderRoots(applicationContext)

        finish()
    }

    private fun loginIsLoading() {
        showLoadingDialog()
    }

    private fun loginIsError(uiResult: UIResult<String>) {
        dismissLoadingDialog()
        when (uiResult.getThrowableOrNull()) {
            is NoNetworkConnectionException, is ServerNotReachableException -> {
                server_status_text.run {
                    text = getString(R.string.error_no_network_connection)
                    setCompoundDrawablesWithIntrinsicBounds(R.drawable.no_network, 0, 0, 0)
                }
                showOrHideBasicAuthFields(shouldBeVisible = false)
            }
            else -> auth_status_text.run {
                text = uiResult.getThrowableOrNull()?.parseError("", resources, true)
                isVisible = true
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.common_error, 0, 0, 0)
            }
        }
    }

    private fun updateAuthTokenTypeAndInstructions(uiResult: UIResult<Boolean?>) {
        val supportsOAuth2 = uiResult.getStoredData()
        authTokenType = if (supportsOAuth2 != null && supportsOAuth2) OAUTH_TOKEN_TYPE else BASIC_TOKEN_TYPE

        instructions_message.run {
            if (loginAction == ACTION_UPDATE_EXPIRED_TOKEN) {
                text = if (AccountTypeUtils.getAuthTokenTypeAccessToken(accountType) == authTokenType) {
                    getString(R.string.auth_expired_oauth_token_toast)
                } else {
                    getString(R.string.auth_expired_basic_auth_toast)
                }
                visibility = VISIBLE
            } else visibility = GONE
        }
    }

    /**
     * Show untrusted cert dialog
     */
    private fun showUntrustedCertDialog(certificateCombinedException: CertificateCombinedException) { // Show a dialog with the certificate info
        val dialog = SslUntrustedCertDialog.newInstanceForFullSslError(certificateCombinedException)
        val fm = supportFragmentManager
        val ft = fm.beginTransaction()
        ft.addToBackStack(null)
        dialog.show(ft, UNTRUSTED_CERT_DIALOG_TAG)
    }

    override fun onSavedCertificate() {
        Timber.d("Server certificate is trusted")
        checkOcServer()
    }

    override fun onCancelCertificate() {
        Timber.d("Server certificate is not trusted")
    }

    override fun onFailedSavingCertificate() {
        Timber.d("Server certificate could not be saved")
        Toast.makeText(this, R.string.ssl_validator_not_saved, Toast.LENGTH_LONG).show()
    }

    // TODO: Create an extension to show or hide a loading dialog from an activity.
    private fun showLoadingDialog() {
        val dialog = LoadingDialog.newInstance(R.string.auth_trying_to_login, true)
        dialog.show(supportFragmentManager, WAIT_DIALOG_TAG)
    }

    private fun dismissLoadingDialog() {
        val frag = supportFragmentManager.findFragmentByTag(WAIT_DIALOG_TAG)
        if (frag is DialogFragment) {
            frag.dismiss()
        }
    }

    /* Show or hide Basic Auth fields and reset its values */
    private fun showOrHideBasicAuthFields(shouldBeVisible: Boolean) {
        account_username_container.run {
            visibility = if (shouldBeVisible) VISIBLE else GONE
            isFocusable = shouldBeVisible
            isEnabled = shouldBeVisible
            if (shouldBeVisible) requestFocus()
        }
        account_password_container.run {
            visibility = if (shouldBeVisible) VISIBLE else GONE
            isFocusable = shouldBeVisible
            isEnabled = shouldBeVisible
        }

        if (!shouldBeVisible) {
            account_username.setText("")
            account_password.setText("")
        }

        auth_status_text.run {
            isVisible = false
            text = ""
        }
    }

    private fun initBrandableOptionsUI() {
        if (!resources.getBoolean(R.bool.show_server_url_input)) {
            hostUrlFrame.visibility = GONE
            centeredRefreshButton.run {
                isVisible = true
                setOnClickListener { checkOcServer() }
            }
        }

        if (resources.getString(R.string.server_url).isNotEmpty()) {
            hostUrlInput.setText(R.string.server_url)
            checkOcServer()
        }

        login_layout.run {
            if (resources.getBoolean(R.bool.use_login_background_image)) {
                login_background_image.visibility = VISIBLE
            } else {
                setBackgroundColor(resources.getColor(R.color.login_background_color))
            }
        }

        welcome_link.run {
            if (resources.getBoolean(R.bool.show_welcome_link)) {
                visibility = VISIBLE
                text = String.format(getString(R.string.auth_register), getString(R.string.app_name))
                setOnClickListener {
                    val openWelcomeLinkIntent =
                        Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.welcome_link_url)))
                    setResult(Activity.RESULT_CANCELED)
                    startActivity(openWelcomeLinkIntent)
                }
            } else visibility = GONE
        }
    }

    /**
     * OAuth step 1: Get authorization code
     */
    private fun startOAuthorization() {
        server_status_text.run {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.progress_small, 0, 0, 0)
            text = resources.getString(R.string.oauth_login_connection)
        }

        mAuthService = OAuthUtils.createAuthorizationService(this)

        val authorizationRequest = OAuthUtils.createAuthorizationRequest(
            serverBaseUrl + "/" + getString(R.string.oauth2_url_endpoint_auth), // auth endpoint
            serverBaseUrl + "/" + getString(R.string.oauth2_url_endpoint_access), // token endpoint
            getString(R.string.oauth2_client_id),
            getString(R.string.oauth2_redirect_uri)
        )

        val completedIntent = Intent(this, LoginActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, completedIntent, 0)

        mAuthService?.performAuthorizationRequest(authorizationRequest, pendingIntent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            handleGetAuthorizationCodeResponse(it)
        }
    }

    private fun handleGetAuthorizationCodeResponse(intent: Intent) {
        val authorizationResponse = AuthorizationResponse.fromIntent(intent)
        val authorizationException = AuthorizationException.fromIntent(intent)
        if (authorizationResponse?.authorizationCode != null) {
            exchangeAuthorizationCodeForTokens(authorizationResponse)
        } else if (authorizationException != null) {
            updateOAuthStatusIconAndText(authorizationException)
            Timber.e(authorizationException, "OAuth request to get authorization code failed")
        }
    }

    /**
     * OAuth step 2: Exchange the received authorization code for access and refresh tokens
     */
    private fun exchangeAuthorizationCodeForTokens(authorizationResponse: AuthorizationResponse) {
        val clientAuth = OAuthUtils.createClientSecretBasic(getString(R.string.oauth2_client_secret))
        mAuthService?.performTokenRequest(
            authorizationResponse.createTokenExchangeRequest(),
            clientAuth,
            handleExchangeAuthorizationCodeForTokensResponse()
        )
    }

    private fun handleExchangeAuthorizationCodeForTokensResponse(): TokenResponseCallback =
        TokenResponseCallback { tokenResponse: TokenResponse?, authorizationException: AuthorizationException? ->
            if (tokenResponse?.accessToken != null && tokenResponse.refreshToken != null) {
                authenticationViewModel.loginOAuth(
                    tokenResponse.additionalParameters[AccountUtils.Constants.KEY_USER_ID]!!,
                    OAUTH_TOKEN_TYPE,
                    tokenResponse.accessToken as String,
                    tokenResponse.refreshToken as String,
                    tokenResponse.scope
                )
            } else if (authorizationException != null) {
                updateOAuthStatusIconAndText(authorizationException)
                Timber.e(
                    authorizationException,
                    "OAuth request to exchange authorization code for tokens failed"
                )
            }
        }

    private fun updateOAuthStatusIconAndText(authorizationException: AuthorizationException) {
        server_status_text.run {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.common_error, 0, 0, 0)
            text =
                if (authorizationException == AuthorizationException.AuthorizationRequestErrors.ACCESS_DENIED) {
                    resources.getString(R.string.auth_oauth_error_access_denied)
                } else {
                    resources.getString(R.string.auth_oauth_error)
                }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_AUTH_TOKEN_TYPE, authTokenType)
    }

    override fun onDestroy() {
        super.onDestroy()
        mAuthService?.dispose()
    }
}