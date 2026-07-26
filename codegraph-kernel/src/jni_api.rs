//! Android JNI 出口。
//!
//! 上游 codegraph 用 napi 把内核暴露给 Node,我们要的是 Android。绑定层刻意做得很薄:
//! **只导出一个函数**,返回 JSON 字符串,Kotlin 侧用 org.json 解析就完事。
//!
//! ## 为什么返回 JSON 而不是原样传那 5 个二进制 buffer
//!
//! 内核内部用的是紧凑二进制(定长行 + 字符串池 arena),上游在 TypeScript 里写了
//! 对应的解码器。要是把 buffer 原样丢过 JNI,就得在 Kotlin 里把那套解码逻辑再实现
//! 一遍 —— 而行宽、字段顺序、StrRef 偏移这些一旦对不上,症状是**解出乱码而不是报错**,
//! 极难查。更糟的是内核以后改布局,两处得同步改。
//!
//! 在 Rust 侧解码成 JSON 只写一次,而且常量([`NODE_ROW_SIZE`] 等)和布局定义就在
//! 隔壁文件里,对不上会编译失败而不是静默错。代价是多一次序列化 —— 索引是一次性的
//! 批量操作,这点开销无所谓。
//!
//! 上游内核代码保持原样未改动,许可见 LICENSE.codegraph(MIT)。

use crate::buffers::{EDGE_ROW_SIZE, NODE_ROW_SIZE, REF_ROW_SIZE};
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

/// 节点类型名。顺序必须和上游 `src/types.ts` 的 `NODE_KINDS` 完全一致 ——
/// 内核写进 buffer 的是这个数组的下标。
const NODE_KINDS: &[&str] = &[
    "file", "module", "class", "struct", "interface", "trait", "protocol",
    "function", "method", "property", "field", "variable", "constant",
    "enum", "enum_member", "type_alias", "namespace", "parameter",
    "import", "export", "route", "component",
];

/// 边类型名。同样对应上游的 `EDGE_KINDS`。
const EDGE_KINDS: &[&str] = &[
    "contains", "calls", "imports", "exports", "extends", "implements",
    "references", "type_of", "returns", "instantiates", "overrides", "decorates",
];

fn kind_name(table: &[&str], idx: u8) -> String {
    table.get(idx as usize).map(|s| s.to_string())
        .unwrap_or_else(|| format!("unknown_{idx}"))
}

fn read_u32(buf: &[u8], at: usize) -> u32 {
    if at + 4 > buf.len() { return 0; }
    u32::from_le_bytes([buf[at], buf[at + 1], buf[at + 2], buf[at + 3]])
}

/// 从 arena 里取一段字符串。StrRef 是 (offset, len) 一对 u32。
///
/// 越界一律返回空串而不是 panic:内核给的偏移理论上总是对的,但一个越界就把整个
/// 索引过程崩掉不值得 —— 少一个字段远比崩溃好。
fn read_str(arena: &[u8], buf: &[u8], at: usize) -> String {
    let off = read_u32(buf, at) as usize;
    let len = read_u32(buf, at + 4) as usize;
    if len == 0 || off + len > arena.len() { return String::new(); }
    String::from_utf8_lossy(&arena[off..off + len]).into_owned()
}

/// JSON 字符串转义。手写是为了不为这一件事拉一个序列化库进来。
fn esc(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

/// 把内核输出的二进制表解码成 JSON。
///
/// 各字段偏移严格照 [`crate::buffers::Tables::push_node`] 等函数的写入顺序,
/// 改那边就必须改这边 —— 行宽用常量对齐,写错会在 debug_assert 处暴露。
fn buffers_to_json(out: &crate::buffers::EmitOut) -> String {
    let arena = &out.arena;
    let mut s = String::from("{\"nodes\":[");

    // ---- nodes ----
    let node_count = out.nodes.len() / NODE_ROW_SIZE;
    for i in 0..node_count {
        let b = i * NODE_ROW_SIZE;
        let row = &out.nodes;
        if i > 0 { s.push(','); }
        s.push_str(&format!(
            "{{\"kind\":\"{}\",\"startLine\":{},\"endLine\":{},\"name\":\"{}\",\"qualifiedName\":\"{}\",\"id\":\"{}\",\"signature\":\"{}\",\"returnType\":\"{}\"}}",
            kind_name(NODE_KINDS, row[b]),
            read_u32(row, b + 4),      // start_line(kind+visibility+flags 共 4 字节在前)
            read_u32(row, b + 8),      // end_line
            esc(&read_str(arena, row, b + 16)),   // name
            esc(&read_str(arena, row, b + 24)),   // qualified_name
            esc(&read_str(arena, row, b + 32)),   // id
            esc(&read_str(arena, row, b + 48)),   // signature
            esc(&read_str(arena, row, b + 72)),   // return_type
        ));
    }

    // ---- edges ----
    s.push_str("],\"edges\":[");
    let edge_count = out.edges.len() / EDGE_ROW_SIZE;
    for i in 0..edge_count {
        let b = i * EDGE_ROW_SIZE;
        let row = &out.edges;
        if i > 0 { s.push(','); }
        s.push_str(&format!(
            "{{\"kind\":\"{}\",\"line\":{},\"from\":\"{}\",\"to\":\"{}\"}}",
            kind_name(EDGE_KINDS, row[b + 8]),
            read_u32(row, b + 12),
            esc(&read_str(arena, row, b + 28)),   // source_id_str
            esc(&read_str(arena, row, b + 36)),   // target_id_str
        ));
    }

    // ---- refs ----
    // 引用是「这里提到了某个名字,但还没解析到具体是谁」。跨文件解析在 Kotlin 侧做,
    // 因为那需要看到整个索引,不是单文件能定的。
    s.push_str("],\"refs\":[");
    let ref_count = out.refs.len() / REF_ROW_SIZE;
    for i in 0..ref_count {
        let b = i * REF_ROW_SIZE;
        let row = &out.refs;
        if i > 0 { s.push(','); }
        s.push_str(&format!(
            "{{\"line\":{},\"name\":\"{}\",\"fromId\":\"{}\"}}",
            read_u32(row, b + 8),
            esc(&read_str(arena, row, b + 16)),   // reference_name
            esc(&read_str(arena, row, b + 32)),   // from_id_str
        ));
    }

    s.push_str("]}");
    s
}

/// `CodeGraphNative.extractFile(path, content, language)` 的实现。
///
/// 失败时返回 `{"error":"..."}` 而不是抛 Java 异常:索引是批量跑的,一个文件解析
/// 失败不该中断整批,调用方看到 error 字段跳过这个文件即可。
#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_com_xincode_tools_CodeGraphNative_extractFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    content: JString,
    language: JString,
) -> jstring {
    let err_json = |e: &mut JNIEnv, msg: &str| -> jstring {
        let j = format!("{{\"error\":\"{}\"}}", esc(msg));
        e.new_string(j).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    };

    let path: String = match env.get_string(&path) { Ok(v) => v.into(), Err(_) => return err_json(&mut env, "bad path") };
    let content: String = match env.get_string(&content) { Ok(v) => v.into(), Err(_) => return err_json(&mut env, "bad content") };
    let language: String = match env.get_string(&language) { Ok(v) => v.into(), Err(_) => return err_json(&mut env, "bad language") };

    // 解析器对畸形输入可能 panic(tree-sitter 的 C 代码尤其)。手机上一个 panic
    // 会带走整个进程,而用户什么提示都看不到 —— 必须挡住。
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        crate::extract_raw(&path, &content, &language)
    }));

    match result {
        Ok(Ok(out)) => {
            let json = buffers_to_json(&out);
            env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
        }
        Ok(Err(e)) => err_json(&mut env, &e),
        Err(_) => err_json(&mut env, "解析器崩溃(文件可能过大或格式异常)"),
    }
}

/// 返回内核支持的语言列表,给 Kotlin 侧判断某个扩展名要不要送进来。
#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_com_xincode_tools_CodeGraphNative_supportedLanguages(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let langs = "[\"java\",\"python\",\"go\",\"c\",\"cpp\",\"rust\",\"csharp\",\"ruby\",\
\"php\",\"swift\",\"kotlin\",\"r\",\"lua\",\"luau\",\"scala\",\"dart\",\
\"typescript\",\"tsx\",\"javascript\",\"jsx\"]";
    env.new_string(langs).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
