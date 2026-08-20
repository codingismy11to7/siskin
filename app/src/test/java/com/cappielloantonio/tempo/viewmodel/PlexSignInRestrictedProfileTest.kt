package com.cappielloantonio.tempo.viewmodel

import android.app.Application
import android.os.Process
import android.os.UserManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// Robolectric: connect()'s guard reads App.getContext().getSystemService(UserManager),
// which is only a live Context under Robolectric -- same reason
// PlexSignInViewModelTest as a whole needs it.
@RunWith(RobolectricTestRunner::class)
class PlexSignInRestrictedProfileTest {
    // connect() sets _state.value directly (not postValue), but LiveData's
    // setValue still asserts it is called from the main thread, and without
    // this rule Robolectric's test thread does not read as one -- the same
    // reason PlexSignInViewModelTest carries it.
    @get:Rule
    val instantTaskRule = InstantTaskExecutorRule()

    // ShadowUserManager's only public setter that takes a UserHandle is this
    // deprecated three-arg overload -- the non-deprecated ones are the
    // protected @Implementation methods the shadow framework calls internally,
    // which are not visible outside org.robolectric.shadows. Deprecated is not
    // the same as gone: this is still the shadow's real, working test seam.
    @Suppress("DEPRECATION")
    @Test
    fun aProfileForbiddenFromAddingAccountsIsToldSoRatherThanStartingAPin() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(UserManager::class.java))
            .setUserRestriction(
                Process.myUserHandle(),
                UserManager.DISALLOW_MODIFY_ACCOUNTS,
                true,
            )

        val viewModel = PlexSignInViewModel(mock<Application>())
        // connect() is the public entry point the Connect button calls;
        // signIn() is private and is reached through it.
        viewModel.connect()

        assertEquals(PlexSignInState.SignInNotAllowed, viewModel.state.value)
    }
}
