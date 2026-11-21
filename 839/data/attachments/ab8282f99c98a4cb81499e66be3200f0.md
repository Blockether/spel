## PATCH http://localhost:40709/echo → 200 OK

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
date: Sat, 21 Nov 2026 18:56:52 GMT
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
curl 'http://localhost:40709/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
