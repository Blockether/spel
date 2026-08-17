## PATCH http://localhost:33225/echo → 200 OK

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
date: Mon, 17 Aug 2026 19:38:46 GMT
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
curl 'http://localhost:33225/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
