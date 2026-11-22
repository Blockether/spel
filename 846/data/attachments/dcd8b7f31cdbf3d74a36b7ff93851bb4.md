## PATCH http://localhost:35839/echo → 200 OK

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
date: Sun, 22 Nov 2026 13:21:09 GMT
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
curl 'http://localhost:35839/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
