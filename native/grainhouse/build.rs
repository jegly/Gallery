use std::{env, fs, path::Path};

fn main() {
    cc::Build::new()
        .file("engine/yseg.c")
        .include("engine")
        .flag_if_supported("-std=gnu99")
        .flag_if_supported("-ffp-contract=fast")
        .opt_level(3)
        .warnings(false)
        .compile("yseg");
    println!("cargo:rerun-if-changed=engine/yseg.c");
    println!("cargo:rerun-if-changed=engine/yseg.h");

    // the film looks, built into the program
    let dir = Path::new(&env::var("CARGO_MANIFEST_DIR").unwrap()).join("assets/films");
    let mut names: Vec<String> = fs::read_dir(&dir)
        .unwrap()
        .filter_map(|e| e.ok()?.file_name().into_string().ok())
        .filter(|n| n.ends_with(".bin"))
        .collect();
    names.sort();
    let mut code = String::from("&[\n");
    for n in &names {
        // relative to the project, so the folder can be moved
        code += &format!("    ({n:?}, include_bytes!(concat!(env!(\"CARGO_MANIFEST_DIR\"), \"/assets/films/{n}\")) as &[u8]),\n");
    }
    code += "]\n";
    fs::write(Path::new(&env::var("OUT_DIR").unwrap()).join("films.rs"), code).unwrap();
    println!("cargo:rerun-if-changed=assets/films");
}
