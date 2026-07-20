## DELETE http://localhost:40791/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 20 Jul 2026 09:17:13 GMT
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
curl 'http://localhost:40791/echo' \
  -X DELETE
```
