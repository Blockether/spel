## PATCH http://localhost:38141/echo → 200 OK

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
date: Sun, 12 Apr 2026 09:39:25 GMT
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
curl 'http://localhost:38141/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
