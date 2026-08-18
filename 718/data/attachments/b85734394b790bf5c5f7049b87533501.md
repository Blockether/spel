## PATCH http://localhost:35395/echo → 200 OK

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
date: Tue, 18 Aug 2026 08:56:50 GMT
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
curl 'http://localhost:35395/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
