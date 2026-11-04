## PATCH http://localhost:36221/echo → 200 OK

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
date: Wed, 04 Nov 2026 12:09:28 GMT
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
curl 'http://localhost:36221/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
