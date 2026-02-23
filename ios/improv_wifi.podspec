#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint improv_wifi.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'improv_wifi'
  s.version          = '0.0.1'
  s.summary          = 'Flutter plugin for Improv WiFi provisioning.'
  s.description      = <<-DESC
A Flutter plugin that wraps the Improv WiFi iOS SDK for BLE-based WiFi provisioning.
                       DESC
  s.homepage         = 'https://github.com/azimut-energy/improv_wifi_flutter'
  s.license          = { :type => 'Apache 2.0', :file => '../LICENSE' }
  s.author           = { 'Azimut Energy' => 'info@azimut.energy' }
  s.source           = { :path => '.' }
  s.source_files = 'improv_wifi/Sources/improv_wifi/**/*'
  s.dependency 'Flutter'
  s.dependency 'Improv-iOS', '~> 0.0.6'
  s.platform = :ios, '15.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  # If your plugin requires a privacy manifest, for example if it uses any
  # required reason APIs, update the PrivacyInfo.xcprivacy file to describe your
  # plugin's privacy impact, and then uncomment this line. For more information,
  # see https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  # s.resource_bundles = {'improv_wifi_privacy' => ['improv_wifi/Sources/improv_wifi/PrivacyInfo.xcprivacy']}
end
