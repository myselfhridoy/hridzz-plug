// use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    // আপনার এক্সটেনশনে ম্যাটেরিয়াল ডিজাইন বা অন্য কোনো বিশেষ লাইব্রেরি আপাতত লাগছে না, তাই শুধু কোর লাইব্রেরিটিই থাক।
}

cloudstream {
    language = "bn" // বাংলা ভাষার জন্য bn
    description = "StreamBD IPTV Extension with Custom Token Parser"
    authors = listOf("myselfhridoy")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    
    tvTypes = listOf(
        "Live"
    )
    
    requiresResources = true

    // আপনি চাইলে এখানে আপনার এক্সটেনশনের জন্য যেকোনো একটি লোগো/আইকনের ডিরেক্ট লিংক দিতে পারেন
    iconUrl = "https://raw.githubusercontent.com/myselfhridoy/hridzz-plug/master/icon.png" 
}
