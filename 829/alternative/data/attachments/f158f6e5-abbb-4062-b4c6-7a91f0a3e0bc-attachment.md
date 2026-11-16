## PATCH http://localhost:35273/echo → 200 OK

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
date: Mon, 16 Nov 2026 01:06:14 GMT
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
curl 'http://localhost:35273/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
