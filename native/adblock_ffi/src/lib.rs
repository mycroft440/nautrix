use adblock::engine::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use std::ffi::{c_char, CStr, CString};
use std::ptr;
use std::sync::Mutex;

struct Handle {
    engine: Mutex<Engine>,
}

fn build_engine(rules: &str) -> Engine {
    let mut set = FilterSet::new(false);
    set.add_filter_list(rules.to_owned(), ParseOptions::default());
    Engine::new_with_filter_set(set)
}

unsafe fn read_cstr<'a>(ptr: *const c_char) -> Option<&'a str> {
    if ptr.is_null() {
        return None;
    }
    // SAFETY: caller guarantees a valid, NUL-terminated string for the duration of the call.
    unsafe { CStr::from_ptr(ptr) }.to_str().ok()
}

#[unsafe(no_mangle)]
pub extern "C" fn nautrix_adblock_create(rules: *const c_char) -> *mut Handle {
    let rules = unsafe { read_cstr(rules) }.unwrap_or("");
    Box::into_raw(Box::new(Handle {
        engine: Mutex::new(build_engine(rules)),
    }))
}

#[unsafe(no_mangle)]
pub extern "C" fn nautrix_adblock_destroy(handle: *mut Handle) {
    if handle.is_null() {
        return;
    }
    // SAFETY: handles are created only by nautrix_adblock_create and destroyed once.
    unsafe { drop(Box::from_raw(handle)) };
}

#[unsafe(no_mangle)]
pub extern "C" fn nautrix_adblock_replace_rules(
    handle: *mut Handle,
    rules: *const c_char,
) -> bool {
    if handle.is_null() {
        return false;
    }
    let Some(rules) = (unsafe { read_cstr(rules) }) else {
        return false;
    };
    // Parse outside of the lock to keep in-flight network checks responsive.
    let replacement = build_engine(rules);
    // SAFETY: handle lifetime is owned by the C++ singleton and outlives this call.
    let handle = unsafe { &*handle };
    match handle.engine.lock() {
        Ok(mut engine) => {
            *engine = replacement;
            true
        }
        Err(_) => false,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn nautrix_adblock_should_block(
    handle: *mut Handle,
    url: *const c_char,
    source_url: *const c_char,
    resource_type: *const c_char,
    method: *const c_char,
) -> bool {
    if handle.is_null() {
        return false;
    }
    let (Some(url), Some(source_url), Some(resource_type), Some(method)) = (
        unsafe { read_cstr(url) },
        unsafe { read_cstr(source_url) },
        unsafe { read_cstr(resource_type) },
        unsafe { read_cstr(method) },
    ) else {
        return false;
    };
    let Ok(request) = Request::new(url, source_url, resource_type, method) else {
        return false;
    };
    // SAFETY: handle lifetime is owned by the C++ singleton and outlives this call.
    let handle = unsafe { &*handle };
    handle
        .engine
        .lock()
        .map(|engine| engine.check_network_request(&request).should_block())
        .unwrap_or(false)
}

/// Returns JSON-encoded UrlSpecificResources. Caller must free with nautrix_adblock_string_free.
#[unsafe(no_mangle)]
pub extern "C" fn nautrix_adblock_cosmetic_resources(
    handle: *mut Handle,
    url: *const c_char,
) -> *mut c_char {
    if handle.is_null() {
        return ptr::null_mut();
    }
    let Some(url) = (unsafe { read_cstr(url) }) else {
        return ptr::null_mut();
    };
    // SAFETY: handle lifetime is owned by the C++ singleton and outlives this call.
    let handle = unsafe { &*handle };
    let Ok(engine) = handle.engine.lock() else {
        return ptr::null_mut();
    };
    let resources = engine.url_cosmetic_resources(url);
    let Ok(json) = serde_json::to_string(&resources) else {
        return ptr::null_mut();
    };
    CString::new(json).map(CString::into_raw).unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn nautrix_adblock_string_free(value: *mut c_char) {
    if value.is_null() {
        return;
    }
    // SAFETY: value must originate from CString::into_raw above.
    unsafe { drop(CString::from_raw(value)) };
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    #[test]
    fn blocks_network_rule_and_returns_cosmetics() {
        let rules = CString::new("||ads.example^\nexample.com##.sponsor").unwrap();
        let handle = nautrix_adblock_create(rules.as_ptr());
        let url = CString::new("https://ads.example/banner.js").unwrap();
        let source = CString::new("https://example.com/").unwrap();
        let kind = CString::new("script").unwrap();
        let method = CString::new("GET").unwrap();
        assert!(nautrix_adblock_should_block(
            handle,
            url.as_ptr(),
            source.as_ptr(),
            kind.as_ptr(),
            method.as_ptr()
        ));

        let page = CString::new("https://example.com/").unwrap();
        let json = nautrix_adblock_cosmetic_resources(handle, page.as_ptr());
        assert!(!json.is_null());
        let text = unsafe { CStr::from_ptr(json) }.to_string_lossy().into_owned();
        assert!(text.contains("sponsor"));
        nautrix_adblock_string_free(json);
        nautrix_adblock_destroy(handle);
    }
}
