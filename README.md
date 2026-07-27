<p align="center">
  <b>Siskin</b>
</p>

---

<p align="center">
  <b>A Subsonic music client for Android Automotive OS</b>
</p>

<div align="center">

<a href="https://github.com/codingismy11to7/siskin/releases/">
    <img alt="Releases" src="https://img.shields.io/github/downloads/codingismy11to7/siskin/total.svg?color=4B95DE&style=flat">
</a>
<a href="https://www.gnu.org/licenses/gpl-3.0">
    <img src="https://img.shields.io/badge/license-GPL%20v3-2B6DBE.svg?style=flat">
</a>
</div>

<p align="center">
    <a href="https://github.com/codingismy11to7/siskin/releases"><b>Download from GitHub Releases</b></a>
</p>
  

**Siskin** is an open-source and privacy focused music client for Subsonic, designed and built natively for Android. It provides a seamless and intuitive music streaming experience, allowing you to access and play your Subsonic music library directly from your Android device. 

Siskin does not rely on magic algorithms to decide what you should listen to. Instead, the interface is built around your listening history, randomness, and optionally integrates with services like Listenbrainz.org and Last.fm to personalize your music experience (These must be supported by your backend). 

The project is a fork of [Tempo](#credits).

[Changelog](CHANGELOG.md)  
[Wiki](USAGE.md)  
[Donate](https://github.com/codingismy11to7/siskin#donate)

**If you find Siskin useful, please consider starring the project on GitHub. It would mean a lot to me and help promote the app to a wider audience.**

### Releases

Release assets include release/debug and 32/64-bit builds of a single variant.

Android Auto and Chromecast support are included in every build.


## Features

- **Subsonic Integration**: Siskin seamlessly integrates with your Subsonic server, providing you with easy access to your entire music collection on the go.
- **Sleek and Intuitive UI**: Enjoy a clean and user-friendly interface designed to enhance your music listening experience, tailored to your preferences and listening history.
- **Browse and Search**: Easily navigate through your music library using various browsing and searching options, including artists, albums, genres, playlists, decades and more.
- **Streaming and Offline Mode**: Stream music directly from your Subsonic server. Offline mode is currently under active development and may have limitations when using multiple servers.
- **Playlist Management**: Create, edit, and manage playlists to curate your perfect music collection.
- **Gapless Playback**: Experience uninterrupted playback with gapless listening mode.
- **ReplayGain**: Volume normalization, preamp offset and clipping prevention if your metadata provide the information.
- **Scrobbling Integration**: Optionally integrate Siskin with Last.fm or Listenbrainz.org to scrobble your played tracks, gather music insights, and further personalize your music recommendations, if supported by your Subsonic server.
- **Podcasts**: If your Subsonic server supports it, listen to podcasts directly within Siskin, expanding your audio entertainment options.
- **Radio**: Siskin can now also search and save Internet radio stations on local device even without a backend subsonic api support. 
- **Instant Mix**: Full refactor of instant mix function which leverages subsonics similarSongs2 by artist/album and similarSongs endpoints to server a larger play queue more reliably.
- **Transcoding Support**: Activate transcoding of tracks on your Subsonic server, allowing you to set a transcoding profile for optimized streaming directly from the app. This feature requires support from your Subsonic server.
- **Multiple Libraries**: Siskin handles multi-library setups gracefully. They are displayed as Library folders.
- **Equalizer**: Option to use built-in or third-party equalizer.
- **Widget**: New widget to keeping the basic controls on your screen at all times.
- **Available in 12 languages**: Currently in Catalan, Chinese, French, German, Italian, Korean, Polish, Portuguese, Russion, Spanish (Spain), Spanish (Latam) and Turkish
- **Chromecast Support**: Stream your music to Chromecast devices. The support is currently in a rudimentary state.
- **Android Auto Support**: Enjoy your favorite music on the go with full Android Auto integration, allowing you to seamlessly control and listen to your tracks directly from your mobile device while driving.

## Screenshot

<p align="center">
  <b>Light theme</b>
</p>

<p align="center">
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_light.png" width=200>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5_light.png" width=200>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6_light.png" width=200>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7_light.png" width=200>
</p>

<br>

<p align="center">
  <b>Dark theme</b>
</p>

<p align="center">
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_dark.png" width=200>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5_dark.png" width=200>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6_dark.png" width=200>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7_dark.png" width=200>
    
</p>

## Android Auto
<p>
    <p align="left">
        <img src="mockup/usage/aa_thumbnails.jpg" width=317 style="margin-right:16px;">
        <img src="mockup/usage/aa_list.jpg" width=317>
    </p>
    <p align="left">
        <img src="mockup/usage/aa_tracks.jpg" width=317 style="margin-right:16px;">
        <img src="mockup/usage/aa_for_you.jpg" width=317>
    </p>
    <p align="left">
        <img src="mockup/usage/aa_AZ.jpg" width=317 style="margin-right:16px;">
        <img src="mockup/usage/aa_search.jpg" width=317>
    </p>
</p>

## Contributing  

Please fork and open PR's against the development branch. Make sure your PR builds successfully. 

If there is an UI change, please include a before/after screenshot and a short video/gif if that helps elaborating the fix/feature in the PR. 

Currently there are no tests but I would love to start on some unit tests. 

Not a hard requirement but any new feature/change should ideally include an update to the nacent documention. 

*Special Thanks*  
All the amazing [contributors](https://github.com/codingismy11to7/siskin/graphs/contributors)❤️

## Donate

<a href="https://liberapay.com/eddyizm/donate"><img alt="Donate using Liberapay" src="https://liberapay.com/assets/widgets/donate.svg"></a>  

bitcoin: `3QVHSSCJvn6yXEcJ3A3cxYLMmbvFsrnUs5`    

[**Buy me a Ko-Fi**](https://ko-fi.com/eddyizm)  

## License

Siskin is released under the [GNU General Public License v3.0](LICENSE). Feel free to modify, distribute, and use the app in accordance with the terms of the license. Contributions to the project are also welcome. 


## Credits
Thanks to the original repo/creator [CappielloAntonio](https://github.com/CappielloAntonio) (forked from v3.9.0)

[SeattleGuy](https://github.com/SeattleGuy) for the new logo design. 
