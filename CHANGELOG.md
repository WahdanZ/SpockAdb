<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Spock Adb Changelog
## [2.0.0]
### Added
- Get Current App BackStack (Activities and nested fragments)
- Add Plugins actions ex GetCurrentFragment,RestartApp,etc
- allow to choose which buttons to show and which not to show
### Changed

### Deprecated

### Removed

### Fixed

- support latest version of AS
- fix get current fragment
- fix If two instances of AS are open, the plugin does not work properly
### Security
## [1.0.9]
### Added

### Changed
- The activity stack now shows activities by app package. This way, the user can clearly see to what package, the activity belongs to.
- The fragment stack can now show nested fragments and follows the same display rules as the activity stack command.
### Deprecated

### Removed

### Fixed

### Security
## [1.0.8]
### Added
- Toggle on/off WiFi or mobile data
- Add text to be input on the device.
### Changed

### Deprecated

### Removed

### Fixed

### Security
## [1.0.7]
### Added
- Restart app with debugger 
- Uninstall and Clear App Data and Restart
- Toggle "Show Taps" setting;
- Toggle "Show Layout Bounds" setting;
- Toggle "Don't Keep Activities" setting;
- Adds option to Grant or Revoke all app permissions at once.

- Change scale of:
    - Window Animation;
    - Transition Animation;
    - Animator Duration.
### Changed

### Deprecated

### Removed

### Fixed

### Security
    
## [1.0.0]
### Added
- Navigate to current active activity in your IDE
- Current BackStack Activities
- Navigate to current active fragments
- Clear application data
- Enable and Disable Permissions of your application
- Kill or Restart Application
