## PATCH http://localhost:41125/echo → 200 OK

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Sun, 09 Aug 2026 19:26:10 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:41125/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
