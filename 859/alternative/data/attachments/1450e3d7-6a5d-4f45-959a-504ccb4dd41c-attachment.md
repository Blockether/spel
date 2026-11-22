## PATCH http://localhost:42799/echo → 200 OK

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
date: Sun, 22 Nov 2026 19:46:37 GMT
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
curl 'http://localhost:42799/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
