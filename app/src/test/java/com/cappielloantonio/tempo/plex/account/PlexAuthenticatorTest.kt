package com.cappielloantonio.tempo.plex.account

import android.content.Intent
import androidx.core.os.BundleCompat
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.ui.activity.CarHostActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlexAuthenticatorTest {
    // androidx.test:core (ApplicationProvider) is not on this module's test
    // classpath -- only androidx.test:monitor arrives transitively via
    // Robolectric. RuntimeEnvironment.getApplication() is the same app Context
    // AppInstanceTest already relies on, with no new dependency.
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun theAccountTypeCarriesTheApplicationIdSoDebugAndReleaseDoNotCollide() {
        // The debug build appends .debug to applicationId; if the account type
        // did not follow, both builds would claim one type and the platform
        // would resolve the conflict arbitrarily.
        val type = context.getString(R.string.plex_account_type)
        assertTrue("expected a .plex suffix, got $type", type.endsWith(".plex"))
        assertEquals(context.packageName + ".plex", type)
    }

    @Test
    fun addAccountSendsTheUserToTheHostActivityAskingForSignIn() {
        val bundle =
            PlexAuthenticator(context)
                .addAccount(null, context.getString(R.string.plex_account_type), null, null, null)

        // Bundle.getParcelable(String) is deprecated as of API 33 (compileSdk
        // here); BundleCompat.getParcelable is the minSdk-28-safe replacement
        // and keeps this test source clean under Kotlin's -Werror.
        val intent =
            BundleCompat.getParcelable(bundle, android.accounts.AccountManager.KEY_INTENT, Intent::class.java)
        assertNotNull(intent)
        assertEquals(CarHostActivity::class.java.name, intent!!.component?.className)
        // Without this the gear-opened path and the sign-in path are
        // indistinguishable, and Add account would land on settings.
        assertTrue(intent.getBooleanExtra(CarHostActivity.EXTRA_FORCE_SIGN_IN, false))
    }
}
