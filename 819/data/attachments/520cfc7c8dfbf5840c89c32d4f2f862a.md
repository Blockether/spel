## DELETE http://localhost:34043/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 04 Nov 2026 05:36:56 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:34043/echo' \
  -X DELETE
```
