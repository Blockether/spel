## GET http://localhost:42679/echo?view=summary → 200 OK

### Request Headers
```
Accept: application/json
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.fake-jwt.signature
User-Agent: spel-showcase/1.0 (+https://github.com/blockether/spel)
X-Request-ID: req-a1b2c3d4-1111
X-Tenant-ID: acme-42
```

### Response Headers
```
content-length: 54
content-type: application/json
date: Fri, 17 Apr 2026 13:13:22 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "view=summary"
}
```

### cURL
```bash
curl 'http://localhost:42679/echo?view=summary' \
  -H 'Accept: application/json' \
  -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.fake-jwt.signature' \
  -H 'User-Agent: spel-showcase/1.0 (+https://github.com/blockether/spel)' \
  -H 'X-Request-ID: req-a1b2c3d4-1111' \
  -H 'X-Tenant-ID: acme-42'
```
