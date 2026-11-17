# Aplicación Windows

## Desarrollo en Linux

Se necesita Rust según `rust-toolchain.toml` y Node/npm según `package.json`.
En el entorno del proyecto, cargar primero el toolchain:

```sh
source /workspace/toolchains/env.sh
```

Verificar y probar el core Rust, sin dependencias Tauri:

```sh
cargo fmt --manifest-path apps/windows/core/Cargo.toml --check
cargo test --manifest-path apps/windows/core/Cargo.toml --locked
cargo clippy --manifest-path apps/windows/core/Cargo.toml --locked --all-targets -- -D warnings
```

Instalar dependencias del frontend y compilar TypeScript/recursos web:

```sh
cd apps/windows
npm ci
npm run build
```

Si el caché predeterminado de npm no existe en el entorno, se puede indicar uno
temporal: `npm ci --cache=/tmp/h1-windows-npm-cache`.

`npm run build` no compila el shell Tauri. El build del shell y la validación de
la aplicación en Windows requieren su entorno Windows; los resultados Linux no
demuestran compatibilidad Windows.
