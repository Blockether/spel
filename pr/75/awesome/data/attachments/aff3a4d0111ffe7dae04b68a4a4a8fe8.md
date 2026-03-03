## PUT http://localhost:46451/echo → 200 OK

### Request Headers
```
PUT http://localhost:46451/echo
```

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
date: Tue, 03 Mar 2026 09:52:17 GMT
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
curl 'http://localhost:46451/echo' \
  -X PUT \
  -d '{"name":"Eve Updated"}'
```
