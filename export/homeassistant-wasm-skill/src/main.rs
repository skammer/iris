use serde::Deserialize;
use serde_json::{json, Map, Value};
use std::io::{self, Read};

const DEFAULT_STATE_LIMIT: usize = 25;
const MAX_STATE_LIMIT: usize = 200;
const MAX_RESPONSE_BYTES: usize = 1024 * 1024;
const DEFAULT_ALLOWED_DOMAINS: [&str; 4] = ["light", "switch", "scene", "script"];

#[link(wasm_import_module = "http")]
unsafe extern "C" {
    fn request(
        request_ptr: *const u8,
        request_len: usize,
        out_ptr: *mut u8,
        out_cap: usize,
        status_ptr: *mut i32,
    ) -> i32;
}

#[allow(dead_code)]
#[derive(Deserialize)]
struct ToolInput {
    tool: Option<String>,
    arguments: Value,
    settings: Option<Value>,
    workspace: Option<String>,
}

struct HaConfig {
    base_url: String,
    api_key: String,
    timeout_ms: u64,
    allowed_domains: Vec<String>,
    global_services: Vec<String>,
}

#[derive(Debug)]
struct ToolError {
    kind: &'static str,
    message: String,
    details: Option<Value>,
}

impl ToolError {
    fn new(kind: &'static str, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
            details: None,
        }
    }

    fn with_details(kind: &'static str, message: impl Into<String>, details: Value) -> Self {
        Self {
            kind,
            message: message.into(),
            details: Some(details),
        }
    }
}

fn main() {
    let output = match run() {
        Ok(value) => value,
        Err(error) => error_output(error),
    };
    println!("{}", serde_json::to_string(&output).unwrap());
}

fn run() -> Result<Value, ToolError> {
    let mut stdin = String::new();
    io::stdin()
        .read_to_string(&mut stdin)
        .map_err(|error| ToolError::new("stdin_read_failed", error.to_string()))?;

    let input: ToolInput = serde_json::from_str(&stdin)
        .map_err(|error| ToolError::new("invalid_input_json", error.to_string()))?;
    let args = input
        .arguments
        .as_object()
        .ok_or_else(|| ToolError::new("invalid_arguments", "arguments must be a JSON object"))?;
    let settings = input.settings.as_ref();
    let config = ha_config(settings)?;
    let action = required_token(args, "action")?;

    match action.as_str() {
        "list_states" => list_states(&config, &action, args),
        "search_states" => list_states(&config, &action, args),
        "get_state" => get_state(&config, args),
        "get_states" => get_states(&config, args),
        "list_services" => list_services(&config),
        "call_service" => call_service(&config, args),
        _ => Err(ToolError::with_details(
            "unsupported_action",
            "action must be get_state, get_states, list_states, search_states, list_services, or call_service",
            json!({ "action": action }),
        )),
    }
}

fn success(action: &str, body: Value, result_text: String) -> Value {
    json!({
        "ok": true,
        "action": action,
        "body": body,
        "result_text": result_text
    })
}

fn error_output(error: ToolError) -> Value {
    let mut output = Map::new();
    output.insert("ok".to_string(), Value::Bool(false));
    output.insert(
        "error_type".to_string(),
        Value::String(error.kind.to_string()),
    );
    output.insert("error".to_string(), Value::String(error.message));
    if let Some(details) = error.details {
        output.insert("details".to_string(), details);
    }
    Value::Object(output)
}

fn ha_config(settings: Option<&Value>) -> Result<HaConfig, ToolError> {
    let settings = settings
        .and_then(Value::as_object)
        .ok_or_else(|| ToolError::new("missing_settings", "settings must be a JSON object"))?;
    let base_url = required_setting(settings, &["ha_host", "base_url"], "ha_host")?;
    let api_key = required_setting(settings, &["ha_api_key", "token"], "ha_api_key")?;
    validate_base_url(&base_url)?;

    Ok(HaConfig {
        base_url,
        api_key,
        timeout_ms: setting_u64(settings, "timeout_ms").unwrap_or(10_000),
        allowed_domains: configured_list(settings, "allowed_domains", &DEFAULT_ALLOWED_DOMAINS),
        global_services: configured_list(settings, "global_services", &[]),
    })
}

fn required_setting(
    settings: &Map<String, Value>,
    keys: &[&str],
    label: &'static str,
) -> Result<String, ToolError> {
    keys.iter()
        .find_map(|key| optional_string(settings, key))
        .ok_or_else(|| {
            ToolError::with_details(
                "missing_required_setting",
                format!("{label} setting is required"),
                json!({ "setting": label, "aliases": keys }),
            )
        })
}

fn setting_u64(settings: &Map<String, Value>, key: &str) -> Option<u64> {
    settings.get(key).and_then(|value| match value {
        Value::Number(number) => number.as_u64(),
        Value::String(value) => value.trim().parse::<u64>().ok(),
        _ => None,
    })
}

fn validate_base_url(value: &str) -> Result<(), ToolError> {
    if value.starts_with("http://") || value.starts_with("https://") {
        Ok(())
    } else {
        Err(ToolError::with_details(
            "invalid_ha_host",
            "ha_host must start with http:// or https://",
            json!({ "ha_host": value }),
        ))
    }
}

fn join_url(base_url: &str, path: &str) -> String {
    format!(
        "{}/{}",
        base_url.trim_end_matches('/'),
        path.trim_start_matches('/')
    )
}

fn ha_request(
    config: &HaConfig,
    method: &str,
    path: &str,
    body: Option<&Value>,
) -> Result<(i32, Value), ToolError> {
    let body_text = match body {
        Some(value) => serde_json::to_string(value)
            .map_err(|error| ToolError::new("request_body_encode_failed", error.to_string()))?,
        None => String::new(),
    };
    let url = join_url(&config.base_url, path);
    let request_json = json!({
        "method": method,
        "url": url,
        "headers": {
            "Authorization": format!("Bearer {}", config.api_key),
            "Content-Type": "application/json",
            "Accept": "application/json"
        },
        "body": if body_text.is_empty() { Value::Null } else { Value::String(body_text) },
        "timeout_ms": config.timeout_ms
    });
    let request_text = serde_json::to_string(&request_json)
        .map_err(|error| ToolError::new("http_request_encode_failed", error.to_string()))?;
    let mut out = vec![0_u8; MAX_RESPONSE_BYTES];
    let mut status = 0_i32;
    let n = unsafe {
        request(
            request_text.as_ptr(),
            request_text.len(),
            out.as_mut_ptr(),
            out.len(),
            &mut status as *mut i32,
        )
    };

    if n < 0 {
        return Err(ToolError::with_details(
            "http_host_error",
            "HTTP host request failed",
            json!({ "code": n, "status": status, "method": method, "url": url }),
        ));
    }

    let body_slice = &out[..n as usize];
    let body_text = std::str::from_utf8(body_slice)
        .map_err(|error| ToolError::new("invalid_utf8_response", error.to_string()))?;
    let parsed = if body_text.trim().is_empty() {
        Value::Null
    } else {
        serde_json::from_str(body_text).map_err(|error| {
            ToolError::with_details(
                "invalid_json_response",
                error.to_string(),
                json!({ "status": status, "method": method, "url": url }),
            )
        })?
    };

    if (200..=299).contains(&status) {
        Ok((status, parsed))
    } else {
        Err(ToolError::with_details(
            "homeassistant_http_error",
            format!("Home Assistant request failed: {status}"),
            json!({ "status": status, "method": method, "url": url, "body": parsed }),
        ))
    }
}

fn list_states(
    config: &HaConfig,
    action: &str,
    args: &Map<String, Value>,
) -> Result<Value, ToolError> {
    let (_, states_json) = ha_request(config, "GET", "/api/states", None)?;
    let states = states_json.as_array().ok_or_else(|| {
        ToolError::new(
            "invalid_states_response",
            "/api/states must return an array",
        )
    })?;
    let query = optional_string(args, "query");
    let domain = optional_token(args, "domain");
    let device_class = optional_token(args, "device_class");
    let limit = state_limit(args.get("limit"));
    let terms = query_terms(query.as_deref());

    let matched: Vec<&Value> = states
        .iter()
        .filter(|state| state_matches_query(&terms, state))
        .filter(|state| state_matches_domain(domain.as_deref(), state))
        .filter(|state| state_matches_device_class(device_class.as_deref(), state))
        .collect();
    let returned: Vec<Value> = matched
        .iter()
        .take(limit)
        .map(|state| state_summary(state))
        .collect();
    let body = json!({
        "entity_count": states.len(),
        "matched": matched.len(),
        "returned": returned.len(),
        "more_available": matched.len() > returned.len(),
        "limit": limit,
        "query": query.clone().unwrap_or_default(),
        "domain": domain,
        "device_class": device_class,
        "entities": returned
    });
    let result_text = states_result_text(action, &body);
    Ok(success(action, body, result_text))
}

fn get_state(config: &HaConfig, args: &Map<String, Value>) -> Result<Value, ToolError> {
    let entity_id = required_string(args, "entity_id")?;
    validate_entity_id(&entity_id)?;
    let (status, body) = ha_request(config, "GET", &format!("/api/states/{entity_id}"), None)?;
    let result_text = format!("homeassistant.get_state ok: {}", state_summary_line(&body));
    Ok(success(
        "get_state",
        json!({ "status": status, "entity_id": entity_id, "body": body }),
        result_text,
    ))
}

fn get_states(config: &HaConfig, args: &Map<String, Value>) -> Result<Value, ToolError> {
    let entity_ids = required_entity_ids(args)?;
    let (_, states_json) = ha_request(config, "GET", "/api/states", None)?;
    let states = states_json.as_array().ok_or_else(|| {
        ToolError::new(
            "invalid_states_response",
            "/api/states must return an array",
        )
    })?;
    let mut entities = Vec::new();
    let mut missing = Vec::new();

    for entity_id in &entity_ids {
        match states.iter().find(|state| {
            value_string(state.get("entity_id")).as_deref() == Some(entity_id.as_str())
        }) {
            Some(state) => entities.push(state_summary(state)),
            None => missing.push(entity_id.clone()),
        }
    }

    let body = json!({
        "requested": entity_ids.len(),
        "returned": entities.len(),
        "missing": missing,
        "entities": entities
    });
    let result_text = selected_states_result_text(&body);
    Ok(success("get_states", body, result_text))
}

fn list_services(config: &HaConfig) -> Result<Value, ToolError> {
    let (_, services_json) = ha_request(config, "GET", "/api/services", None)?;
    let domains = services_json.as_array().ok_or_else(|| {
        ToolError::new(
            "invalid_services_response",
            "/api/services must return an array",
        )
    })?;
    let mut compact = Vec::new();
    let mut service_count = 0_usize;

    for domain_entry in domains {
        let domain = value_string(domain_entry.get("domain")).unwrap_or_default();
        let mut names = match domain_entry.get("services").and_then(Value::as_object) {
            Some(services) => services.keys().cloned().collect::<Vec<_>>(),
            None => Vec::new(),
        };
        names.sort();
        service_count += names.len();
        compact.push(json!({ "domain": domain, "services": names }));
    }

    let body = json!({
        "domain_count": compact.len(),
        "service_count": service_count,
        "domains": compact
    });
    let result_text = services_result_text(&body);
    Ok(success("list_services", body, result_text))
}

fn call_service(config: &HaConfig, args: &Map<String, Value>) -> Result<Value, ToolError> {
    let domain = required_token(args, "domain")?;
    let service = required_token(args, "service")?;
    validate_service_name(&domain, "domain")?;
    validate_service_name(&service, "service")?;

    if !config
        .allowed_domains
        .iter()
        .any(|item| item == "all" || item == &domain)
    {
        return Err(ToolError::with_details(
            "homeassistant_domain_not_allowed",
            "Home Assistant domain is not allowlisted",
            json!({ "domain": domain, "allowed_domains": config.allowed_domains }),
        ));
    }

    let entity_id = optional_string(args, "entity_id");
    if let Some(entity_id) = entity_id.as_deref() {
        validate_entity_id(entity_id)?;
    }

    let service_id = format!("{domain}.{service}");
    if entity_id.is_none()
        && !config
            .global_services
            .iter()
            .any(|item| item == &service_id)
    {
        return Err(ToolError::with_details(
            "entity_required",
            "call_service requires entity_id unless the service is globally allowlisted",
            json!({ "domain": domain, "service": service }),
        ));
    }

    let mut body = match args.get("data") {
        Some(Value::Object(data)) => data.clone(),
        Some(_) => {
            return Err(ToolError::new(
                "invalid_service_data",
                "data must be a JSON object when provided",
            ))
        }
        None => Map::new(),
    };
    if let Some(entity_id) = entity_id.as_ref() {
        body.insert("entity_id".to_string(), Value::String(entity_id.clone()));
    }

    let (status, response_body) = ha_request(
        config,
        "POST",
        &format!("/api/services/{domain}/{service}"),
        Some(&Value::Object(body)),
    )?;
    let result_text = call_service_result_text(
        &domain,
        &service,
        entity_id.as_deref(),
        status,
        &response_body,
    );
    Ok(success(
        "call_service",
        json!({
            "status": status,
            "domain": domain,
            "service": service,
            "entity_id": entity_id,
            "body": response_body
        }),
        result_text,
    ))
}

fn required_string(args: &Map<String, Value>, key: &'static str) -> Result<String, ToolError> {
    optional_string(args, key).ok_or_else(|| {
        ToolError::with_details(
            "missing_required_argument",
            format!("{key} is required"),
            json!({ "argument": key }),
        )
    })
}

fn required_token(args: &Map<String, Value>, key: &'static str) -> Result<String, ToolError> {
    let raw = required_string(args, key)?;
    Ok(normalize_token(&raw))
}

fn required_entity_ids(args: &Map<String, Value>) -> Result<Vec<String>, ToolError> {
    let values = args
        .get("entity_ids")
        .and_then(Value::as_array)
        .ok_or_else(|| {
            ToolError::with_details(
                "missing_required_argument",
                "entity_ids must be a non-empty array",
                json!({ "argument": "entity_ids" }),
            )
        })?;
    let mut entity_ids = Vec::new();
    for value in values {
        let entity_id = value_string(Some(value))
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty())
            .ok_or_else(|| {
                ToolError::new(
                    "invalid_entity_ids",
                    "every entity_ids value must be a string",
                )
            })?;
        validate_entity_id(&entity_id)?;
        if !entity_ids.contains(&entity_id) {
            entity_ids.push(entity_id);
        }
    }
    if entity_ids.is_empty() || entity_ids.len() > MAX_STATE_LIMIT {
        return Err(ToolError::with_details(
            "invalid_entity_ids",
            format!("entity_ids must contain 1 to {MAX_STATE_LIMIT} unique values"),
            json!({ "count": entity_ids.len() }),
        ));
    }
    Ok(entity_ids)
}

fn optional_string(args: &Map<String, Value>, key: &str) -> Option<String> {
    args.get(key)
        .and_then(|value| value_string(Some(value)))
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

fn optional_token(args: &Map<String, Value>, key: &str) -> Option<String> {
    optional_string(args, key).map(|value| normalize_token(&value))
}

fn value_string(value: Option<&Value>) -> Option<String> {
    match value? {
        Value::String(value) => Some(value.clone()),
        Value::Number(value) => Some(value.to_string()),
        Value::Bool(value) => Some(value.to_string()),
        _ => None,
    }
}

fn normalize_token(value: &str) -> String {
    value.trim().replace('-', "_").to_ascii_lowercase()
}

fn validate_service_name(value: &str, field: &'static str) -> Result<(), ToolError> {
    if !value.is_empty()
        && value
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || ch == '_')
    {
        Ok(())
    } else {
        Err(ToolError::with_details(
            "invalid_service_name",
            format!("{field} must match [A-Za-z0-9_]+"),
            json!({ field: value }),
        ))
    }
}

fn validate_entity_id(value: &str) -> Result<(), ToolError> {
    if !value.is_empty()
        && value
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || ch == '_' || ch == '.')
    {
        Ok(())
    } else {
        Err(ToolError::with_details(
            "invalid_entity_id",
            "entity_id must match [A-Za-z0-9_.]+",
            json!({ "entity_id": value }),
        ))
    }
}

fn configured_list(settings: &Map<String, Value>, key: &str, default: &[&str]) -> Vec<String> {
    match settings.get(key) {
        Some(Value::Array(values)) => values
            .iter()
            .filter_map(|value| value_string(Some(value)))
            .map(|value| normalize_token(&value))
            .filter(|value| !value.is_empty())
            .collect(),
        Some(Value::String(value)) => value
            .split(',')
            .map(normalize_token)
            .filter(|value| !value.is_empty())
            .collect(),
        _ => default.iter().map(|value| value.to_string()).collect(),
    }
}

fn state_limit(value: Option<&Value>) -> usize {
    value
        .and_then(Value::as_u64)
        .map(|value| value as usize)
        .unwrap_or(DEFAULT_STATE_LIMIT)
        .clamp(1, MAX_STATE_LIMIT)
}

fn query_terms(query: Option<&str>) -> Vec<String> {
    query
        .unwrap_or_default()
        .to_ascii_lowercase()
        .split(|ch: char| !(ch.is_ascii_alphanumeric() || ch == '_'))
        .map(str::trim)
        .filter(|term| !term.is_empty())
        .map(ToString::to_string)
        .collect()
}

fn state_matches_query(terms: &[String], state: &Value) -> bool {
    if terms.is_empty() {
        return true;
    }
    let haystack = [
        value_string(state.get("entity_id")),
        value_string(state.get("state")),
        state_attr_string(state, "friendly_name"),
        state_attr_string(state, "device_class"),
        state_attr_string(state, "unit_of_measurement"),
    ]
    .into_iter()
    .flatten()
    .collect::<Vec<_>>()
    .join(" ")
    .to_ascii_lowercase();
    terms.iter().all(|term| haystack.contains(term))
}

fn state_matches_domain(domain: Option<&str>, state: &Value) -> bool {
    match domain {
        Some(domain) => state_domain(state).as_deref() == Some(domain),
        None => true,
    }
}

fn state_matches_device_class(device_class: Option<&str>, state: &Value) -> bool {
    match device_class {
        Some(device_class) => state_attr_string(state, "device_class")
            .map(|value| normalize_token(&value) == device_class)
            .unwrap_or(false),
        None => true,
    }
}

fn state_domain(state: &Value) -> Option<String> {
    value_string(state.get("entity_id")).and_then(|entity_id| {
        entity_id
            .split_once('.')
            .map(|(domain, _)| normalize_token(domain))
    })
}

fn state_attr_string(state: &Value, key: &str) -> Option<String> {
    state
        .get("attributes")
        .and_then(|attributes| attributes.get(key))
        .and_then(|value| value_string(Some(value)))
}

fn state_summary(state: &Value) -> Value {
    let mut summary = Map::new();
    if let Some(value) = value_string(state.get("entity_id")) {
        summary.insert("entity_id".to_string(), Value::String(value));
    }
    if let Some(value) = value_string(state.get("state")) {
        summary.insert("state".to_string(), Value::String(value));
    }
    for key in ["friendly_name", "device_class", "unit_of_measurement"] {
        if let Some(value) = state_attr_string(state, key) {
            summary.insert(key.to_string(), Value::String(value));
        }
    }
    if let Some(value) = value_string(state.get("last_updated")) {
        summary.insert("last_updated".to_string(), Value::String(value));
    }
    Value::Object(summary)
}

fn one_line(value: Option<String>) -> Option<String> {
    let value = value?;
    let text = value.split_whitespace().collect::<Vec<_>>().join(" ");
    if text.is_empty() {
        None
    } else {
        Some(text)
    }
}

fn state_summary_line(state: &Value) -> String {
    let summary = if state.get("attributes").is_some() {
        state_summary(state)
    } else {
        state.clone()
    };
    let entity_id =
        value_string(summary.get("entity_id")).unwrap_or_else(|| "<unknown>".to_string());
    let state_value = value_string(summary.get("state")).unwrap_or_else(|| "<unknown>".to_string());
    let details = [
        one_line(value_string(summary.get("friendly_name"))),
        one_line(value_string(summary.get("device_class"))),
        one_line(value_string(summary.get("unit_of_measurement"))),
        one_line(value_string(summary.get("last_updated"))),
    ]
    .into_iter()
    .flatten()
    .collect::<Vec<_>>()
    .join(" | ");

    if details.is_empty() {
        format!("{entity_id} = {state_value}")
    } else {
        format!("{entity_id} = {state_value} | {details}")
    }
}

fn states_result_text(action: &str, body: &Value) -> String {
    let entities = body
        .get("entities")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let mut lines = vec![
        format!(
            "homeassistant.{action} ok: returned {}/{} matched, total {}, limit {}, more {}",
            body.get("returned").and_then(Value::as_u64).unwrap_or(0),
            body.get("matched").and_then(Value::as_u64).unwrap_or(0),
            body.get("entity_count")
                .and_then(Value::as_u64)
                .unwrap_or(0),
            body.get("limit").and_then(Value::as_u64).unwrap_or(0),
            body.get("more_available")
                .and_then(Value::as_bool)
                .unwrap_or(false)
        ),
        format!(
            "filters: query={:?} | domain={} | device_class={}",
            value_string(body.get("query")).unwrap_or_default(),
            value_string(body.get("domain")).unwrap_or_else(|| "*".to_string()),
            value_string(body.get("device_class")).unwrap_or_else(|| "*".to_string())
        ),
    ];
    lines.extend(entities.iter().map(state_summary_line));
    lines.join("\n")
}

fn selected_states_result_text(body: &Value) -> String {
    let requested = body.get("requested").and_then(Value::as_u64).unwrap_or(0);
    let returned = body.get("returned").and_then(Value::as_u64).unwrap_or(0);
    let missing = body
        .get("missing")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let entities = body
        .get("entities")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let mut lines = vec![format!(
        "homeassistant.get_states ok: returned {returned}/{requested} requested, missing {}",
        missing.len()
    )];
    if !missing.is_empty() {
        lines.push(format!(
            "missing: {}",
            missing
                .iter()
                .filter_map(|value| value_string(Some(value)))
                .collect::<Vec<_>>()
                .join(", ")
        ));
    }
    lines.extend(entities.iter().map(state_summary_line));
    lines.join("\n")
}

fn services_result_text(body: &Value) -> String {
    let mut lines = vec![format!(
        "homeassistant.list_services ok: domains {}, services {}",
        body.get("domain_count")
            .and_then(Value::as_u64)
            .unwrap_or(0),
        body.get("service_count")
            .and_then(Value::as_u64)
            .unwrap_or(0)
    )];
    if let Some(domains) = body.get("domains").and_then(Value::as_array) {
        for domain in domains {
            let name = value_string(domain.get("domain")).unwrap_or_default();
            let services = domain
                .get("services")
                .and_then(Value::as_array)
                .map(|items| {
                    items
                        .iter()
                        .filter_map(|item| value_string(Some(item)))
                        .collect::<Vec<_>>()
                        .join(", ")
                })
                .unwrap_or_default();
            lines.push(format!("{name}: {services}"));
        }
    }
    lines.join("\n")
}

fn call_service_result_text(
    domain: &str,
    service: &str,
    entity_id: Option<&str>,
    status: i32,
    body: &Value,
) -> String {
    let mut lines = vec![format!(
        "homeassistant.call_service ok: {domain}.{service}{} status={status}",
        entity_id
            .map(|entity_id| format!(" {entity_id}"))
            .unwrap_or_default()
    )];
    if let Some(states) = body.as_array() {
        lines.extend(states.iter().map(state_summary_line));
    }
    lines.join("\n")
}
