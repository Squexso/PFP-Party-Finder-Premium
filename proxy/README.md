# PFP secrets proxy

Holds the Hypixel API key so the mod does not have to.

```
npm i -g wrangler
wrangler login
cd proxy
wrangler secret put HYPIXEL_KEY   # paste the key, it is never stored in this repo
wrangler deploy                   # prints https://pfp-secrets.<account>.workers.dev
```

Put that URL into `SecretTracker.PROXY_URL` and rebuild the mod.
