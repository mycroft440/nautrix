use adblock::engine::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use std::ptr;
use std::sync::{OnceLock, RwLock};

static ENGINE: OnceLock<RwLock<Engine>> = OnceLock::new();

fn engine() -> &'static RwLock<Engine> {
    ENGINE.get_or_init(|| RwLock::new(Engine::default()))
}

fn from_java(env: &mut JNIEnv, value: JString) -> Option<String> {
    env.get_string(&value).ok().map(Into::into)
}

fn build_engine(rules: String) -> Engine {
    let mut filter_set = FilterSet::new(false);
    filter_set.add_filter_list(rules, ParseOptions::default());
    Engine::new_with_filter_set(filter_set)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nautrix_browser_AdBlockEngine_nativeReplaceRules(
    mut env: JNIEnv,
    _class: JClass,
    rules: JString,
) -> jboolean {
    let Some(rules) = from_java(&mut env, rules) else {
        return 0;
    };
    let replacement = build_engine(rules);
    match engine().write() {
        Ok(mut current) => {
            *current = replacement;
            1
        }
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nautrix_browser_AdBlockEngine_nativeShouldBlock(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    source_url: JString,
    resource_type: JString,
) -> jboolean {
    let (Some(url), Some(source_url), Some(resource_type)) = (
        from_java(&mut env, url),
        from_java(&mut env, source_url),
        from_java(&mut env, resource_type),
    ) else {
        return 0;
    };
    let Ok(request) = Request::new(&url, &source_url, &resource_type, "GET") else {
        return 0;
    };
    engine()
        .read()
        .map(|current| current.check_network_request(&request).should_block() as jboolean)
        .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nautrix_browser_AdBlockEngine_nativeCosmeticResources(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let Some(url) = from_java(&mut env, url) else {
        return ptr::null_mut();
    };
    let Ok(current) = engine().read() else {
        return ptr::null_mut();
    };
    let Ok(json) = serde_json::to_string(&current.url_cosmetic_resources(&url)) else {
        return ptr::null_mut();
    };
    env.new_string(json)
        .map(JString::into_raw)
        .unwrap_or(ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blocks_easylist_syntax() {
        let engine = build_engine("||ads.example^\nexample.com##.sponsor".to_owned());
        let request = Request::new(
            "https://ads.example/banner.js",
            "https://example.com/",
            "script",
            "GET",
        )
        .unwrap();
        assert!(engine.check_network_request(&request).should_block());
        assert!(engine
            .url_cosmetic_resources("https://example.com/")
            .hide_selectors
            .contains(".sponsor"));
    }
}
