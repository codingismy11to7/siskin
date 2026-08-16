package com.cappielloantonio.tempo.plex.account

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** How the system reaches [PlexAuthenticator]; it binds this and nothing else. */
class PlexAuthenticatorService : Service() {

    override fun onBind(intent: Intent?): IBinder =
        PlexAuthenticator(this).iBinder
}
