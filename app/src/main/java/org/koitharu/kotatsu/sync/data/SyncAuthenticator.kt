package org.koitharu.kotatsu.sync.data

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.koitharu.kotatsu.R

class SyncAuthenticator(
	context: Context,
	private val account: Account,
	private val authApi: SyncAuthApi,
) : Authenticator {

	private val accountManager = AccountManager.get(context)
	private val tokenType = context.getString(R.string.account_type_sync)

	override fun authenticate(route: Route?, response: Response): Request? {
		val newToken = tryRefreshToken() ?: return null
		accountManager.setAuthToken(account, tokenType, newToken)
		return response.request.newBuilder()
			.header("Authorization", "Bearer $newToken")
			.build()
	}

	private fun tryRefreshToken() = runCatching {
		runBlocking {
			authApi.authenticate(
				account.name,
				accountManager.getPassword(account),
			)
		}
	}.getOrNull()
}
