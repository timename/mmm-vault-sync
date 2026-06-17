# MMMVaultSync

Lightweight cross-server Vault balance sync for a Velocity + Paper/Purpur network.

## What it does

- Uses MySQL as the authoritative balance store.
- Pulls the latest balance when a player joins a backend server.
- Detects local balance changes for online players and writes them back asynchronously.
- Periodically refreshes online players from MySQL to pick up remote changes.
- Avoids main-thread database access.

## What it does not do

- It does not replace your existing Vault economy provider.
- It does not magically make offline balance edits on another server instantly visible unless a player joins or the periodic refresh catches it.
- It is designed for the common case where a player is only online on one backend server at a time.

## Install

1. Put `target/mmm-vault-sync-1.3.0.jar` into both backend servers' `plugins` folders.
2. Start each server once so the plugin generates `plugins/MMMVaultSync/config.yml`.
3. Set a unique `server-id` on each backend:
   - Survival: `server-id: survival`
   - Adventure: `server-id: adventure`
4. Keep the same MySQL connection settings on both servers.
5. Restart both backend servers.

## Notes

- Default sync table: `mmm_vault_sync_balances`
- Commands:
  - `/mmmvaultsync maintenance on|off`
  - `/mmmvaultsync drain`
  - `/mmmvaultsync verify`
  - `/mmmvaultsync reload confirm`
  - `/mmmvaultsync status`
  - `/mmmvaultsync sync <player>`
- Permission: `mmmvaultsync.admin`

## Plugin API

- Bukkit service: `local.mmm.vaultsync.api.VaultSyncStateService`
- State enum: `local.mmm.vaultsync.api.SyncPhase`
- Event: `local.mmm.vaultsync.api.VaultSyncPhaseChangeEvent`

This API is read-only by design. Other plugins can observe sync state without getting any write access to maintenance, drain, verify, or reload controls.
