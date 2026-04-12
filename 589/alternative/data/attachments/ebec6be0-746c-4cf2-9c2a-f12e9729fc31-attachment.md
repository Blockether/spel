## PATCH http://localhost:37387/echo → 200 OK

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
date: Sun, 12 Apr 2026 15:50:55 GMT
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
curl 'http://localhost:37387/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
