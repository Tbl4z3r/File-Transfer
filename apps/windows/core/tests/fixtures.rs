use file_transfer_core::model::*;
use serde_json::Value;

#[test]
fn normative_vectors() {
    let vectors: Value = serde_json::from_str(include_str!(
        "../../../../protocol/fixtures/h1-vectors.json"
    ))
    .unwrap();
    for row in vectors["sizes"].as_array().unwrap() {
        assert_eq!(
            row["value"].as_str().and_then(parse_size).is_some(),
            row["valid"].as_bool().unwrap(),
            "size {}",
            row["value"]
        );
    }
    for row in vectors["names"].as_array().unwrap() {
        assert_eq!(
            valid_name(row["value"].as_str().unwrap()),
            row["valid"].as_bool().unwrap(),
            "name {}",
            row["value"]
        );
    }
    for row in vectors["ids"].as_array().unwrap() {
        assert_eq!(
            valid_id(row["value"].as_str().unwrap()),
            row["valid"].as_bool().unwrap(),
            "id {}",
            row["value"]
        );
    }
    for row in vectors["hashes"].as_array().unwrap() {
        let hex = row["bytes_hex"].as_str().unwrap();
        let bytes: Vec<u8> = (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
            .collect();
        assert_eq!(digest(&bytes), row["sha256"].as_str().unwrap());
        assert_eq!(
            bytes.len() as u64,
            parse_size(row["size_bytes"].as_str().unwrap()).unwrap()
        );
    }
    for key in [
        "created_upload",
        "verification_upload",
        "completed_download",
        "failed_upload",
    ] {
        serde_json::from_value::<Transfer>(vectors["messages"][key].clone()).unwrap();
    }
}
#[test]
fn strict_json_and_utf8_names() {
    for bad in [
        r#"{"file_name":"a","file_name":"b","size_bytes":"3"}"#,
        r#"{"file_name":"a","size_bytes":3}"#,
        r#"{"file_name":"a","size_bytes":"3","path":"/tmp/x"}"#,
        r#"{"file_name":null,"size_bytes":"3"}"#,
    ] {
        assert!(serde_json::from_str::<CreateUpload>(bad).is_err());
    }
    assert!(!valid_name(&"é".repeat(91)));
    assert!(valid_name(&"é".repeat(90)));
}
