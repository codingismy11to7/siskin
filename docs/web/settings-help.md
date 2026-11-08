# Settings help

What each of Siskin's settings does. In the car, each category in Settings has
a Help button that shows its part of this page.

## Playback {#playback}

### Keep the music playing

When the last track in the queue starts, Siskin adds more music after it:
tracks similar to the one playing, or random tracks from your library if your
Plex server finds no similar ones. It does nothing while repeat all is on.
Off by default.

### Volume leveling

Plays tracks at a more even volume, using the ReplayGain information stored in
many music files. Tracks from the same album keep their volume relative to each
other, and a file without that information plays unchanged. A change takes
effect at the next track. Off by default.

### Cross-fade between tracks

The end of each track fades out while the next one starts. Each fade begins
where the song itself starts to fade, so it lasts between 2 and 10 seconds
depending on the song. Tracks from the same album still play back to back, and
a track your Plex server has not analyzed for loudness plays without a fade.
On by default.

## Browse {#browse}

### Show artists by initial

The Artists tab opens on a list of letters, each showing how many artists it
holds. Plex files each artist by its sort name, which can differ from the name
shown, so The Beatles may be under B. With this off, the tab splits artists
into alphabetical groups of 50, labeled with a range such as "Beck - Cake". A
small library shows one list either way. On by default.

### Show Artist Mix

Puts an Artist Mix row at the top of each artist's page, above their albums.
It plays that artist's tracks in shuffled order. On by default.

### Show Artist Radio

Puts an Artist Radio row on each artist's page, above their albums and after
Artist Mix when that is shown. It plays your Plex server's station for that
artist: the artist and music like it, with more tracks added as it plays. Off
by default.

### Customize tabs

Choose the car's tabs by dragging the list into the order you want. The top
three become tabs, and the first one is where the car opens. More is always the
fourth tab and holds everything below the top three. The row shows the three
current tabs, and the reset button at the top of the screen puts back the
default order: Playlists, Mixes, Radio.

### Show library quick switch

Adds Select Library at the bottom of More, where you can switch to another
library or Plex server while driving. Turn it on if your account has more than
one music library. Off by default.

## Storage {#storage}

### Cache size

Music you have played is kept on the head unit, so it starts without waiting
and is not downloaded again. Automatic uses a tenth of the head unit's storage,
between 1 and 8 GB. You can also pick a fixed size, up to half the head unit's
storage, or Off, which deletes the cached music and also stops precaching. A
new size applies right away, and a smaller one removes the oldest music first.
The row shows how much of the cache is in use.

### Precache

How many upcoming tracks Siskin downloads while the current one plays, so a
skip starts at once and a short loss of signal does not stop the music. Choose
Off, 1, 2, 3, 5 or 10. The default is 2. Precaching uses the music cache, so it
pauses while Cache size is Off.

## Account {#account}

### Switch user

This row only appears when your Plex Home has more than one member. It shows
who is listening, and tapping it lets you pick someone else, entering their PIN
if their profile has one. Switching stops what is playing and clears the queue.
Playback, ratings and history then belong to the person listening.

### Sign out

Stops playback and signs Siskin out of your Plex account on this car. The
sign-in screen opens so you can sign in again, as yourself or with another
account.
