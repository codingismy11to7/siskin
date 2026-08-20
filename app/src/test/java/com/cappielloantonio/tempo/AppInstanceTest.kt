package com.cappielloantonio.tempo

import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [App.getInstance] hands back the Application the system created, not one this
 * class built for itself.
 *
 * The distinction is invisible from almost every call site, which is why it
 * survived: 34 of the 35 `App.getInstance()` uses in main read `.preferences`
 * off it, and that is a static field assigned from the real context in
 * `onCreate` -- so it answers correctly no matter which instance returns it.
 * Only a caller using the result *as a Context* can tell, and it finds out by
 * NPE, because an Application built with `new` has no base context attached.
 */
@RunWith(RobolectricTestRunner::class)
class AppInstanceTest {
    @Test
    fun getInstanceIsTheApplicationTheSystemCreated() {
        assertSame(RuntimeEnvironment.getApplication(), App.getInstance())
    }

    /**
     * The property every Context call needs and a `new App()` does not have.
     * Asserted separately from identity above because it is the actual failure
     * mode -- `MetadataRetriever.Builder(App.getInstance(), item)` reaching a
     * detached Application does not report a wrong instance, it throws.
     */
    @Test
    fun getInstanceHasABaseContextAttached() {
        assertSame(RuntimeEnvironment.getApplication(), App.getInstance().applicationContext)
    }

    @Test
    fun getContextIsTheApplicationContext() {
        assertSame(RuntimeEnvironment.getApplication(), App.getContext())
    }
}
