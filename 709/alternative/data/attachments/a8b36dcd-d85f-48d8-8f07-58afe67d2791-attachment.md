## PUT http://localhost:38425/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Mon, 17 Aug 2026 07:15:08 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:38425/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
